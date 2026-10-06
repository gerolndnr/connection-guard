package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import java.util.*;

/** Pure final-decision view. A fast cached lookup can expire while the other scope is still pending. */
public final class LookupFreshness {
    private LookupFreshness() { }
    public static VpnResult vpn(VpnResult input, long asOf) {
        boolean expired = input.getValidUntil() != 0 && asOf >= input.getValidUntil();
        List<ProviderVote> votes = new ArrayList<>();
        for (ProviderVote vote : input.getVotes()) {
            if (!vote.isFresh(asOf)) {
                expired = true;
                votes.add(new ProviderVote(vote.getProvider(), ProviderVote.Status.UNKNOWN, FailureReason.STALE_DATA,
                        vote.getDurationMillis(), DetectionDetails.empty().withDataAsOf(vote.getDetails().getDataAsOf()), 0, vote.getSourceVersion(), vote.isVoting()));
            } else votes.add(vote);
        }
        if (!expired) return input;
        VpnResult result = new VpnResult(input.getIpAddress(), false);
        int positive = 0, complete = 0, voting = 0;
        for (ProviderVote vote : votes) if (vote.isVoting()) {
            voting++; if (vote.getStatus() != ProviderVote.Status.UNKNOWN) complete++; if (vote.getStatus() == ProviderVote.Status.POSITIVE) positive++;
        }
        result.setPositiveThreshold(input.getPositiveThreshold());
        if (positive >= input.getPositiveThreshold()) result.setStatus(ProviderVote.Status.POSITIVE);
        else if (voting > 0 && complete == voting) result.setStatus(ProviderVote.Status.NEGATIVE);
        else result.setUnknown(FailureReason.STALE_DATA);
        result.setCachedOn(input.getCachedOn()); result.setFromCache(input.isFromCache());
        if (votes.isEmpty()) votes.add(new ProviderVote("lookup", ProviderVote.Status.UNKNOWN, FailureReason.STALE_DATA, 0));
        result.setVotes(votes); result.setSourceVersion(input.getSourceVersion());
        // Reuse the captured threshold; expired metadata cannot erase a still-valid independent positive.
        return result;
    }
    public static GeoLookup geo(GeoLookup input, long asOf) {
        if (input.getResult().isPresent()) {
            GeoResult result = input.getResult().get();
            if (result.getValidUntil() != 0 && asOf >= result.getValidUntil()) return new GeoLookup(Optional.empty(), FailureReason.STALE_DATA, input.isCached(), input.getDurationMillis());
        }
        return input;
    }
}
