package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookupFreshnessTest {
    @Test void cachedVpnThatExpiresDuringTheOtherScopeCannotTriggerAVpnBlockOrMutateTheStoredFacts() {
        VpnResult cached = new VpnResult("192.0.2.1", true); cached.setFromCache(true); cached.setCachedOn(1000); cached.setValidUntil(2000);
        DetectionDetails tor = new DetectionDetails(Collections.singletonMap(DetectionDetails.Type.TOR, true), null, null, null, null, null, null);
        cached.setVotes(Collections.singletonList(new ProviderVote("local.tor", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1, tor, 2000, null)));
        CompletableFuture<GeoLookup> slowGeo = new CompletableFuture<>();
        CompletableFuture<VpnResult> decision = CompletableFuture.completedFuture(cached).thenCombine(slowGeo, (vpn, geo) -> LookupFreshness.vpn(vpn, 3000));
        assertFalse(decision.isDone()); slowGeo.complete(new GeoLookup(Optional.empty(), FailureReason.TIMEOUT, false, 2000));
        VpnResult current = decision.join(); assertFalse(current.isVpn()); assertEquals(ProviderVote.Status.UNKNOWN, current.getStatus());
        assertEquals(FailureReason.STALE_DATA, current.getVotes().get(0).getReason()); assertNull(current.getVotes().get(0).getDetails().get(DetectionDetails.Type.TOR));
        assertTrue(current.isFromCache()); assertEquals(1000, current.getCachedOn());
        assertTrue(cached.isVpn()); assertEquals(Boolean.TRUE, cached.getVotes().get(0).getDetails().get(DetectionDetails.Type.TOR));
        assertSame(cached, LookupFreshness.vpn(cached, 1999));
    }
    @Test void countryThatExpiresWhileVpnIsPendingCannotTriggerACountryBlock() {
        GeoResult country = new GeoResult("192.0.2.1", "DE", "Unknown", "Unknown"); country.setValidUntil(2000);
        GeoLookup cached = new GeoLookup(Optional.of(country), FailureReason.NONE, true, 1);
        GeoLookup expired = LookupFreshness.geo(cached, 2000);
        assertFalse(expired.getResult().isPresent()); assertEquals(FailureReason.STALE_DATA, expired.getReason()); assertTrue(expired.isCached());
        assertTrue(cached.getResult().isPresent()); assertSame(cached, LookupFreshness.geo(cached, 1999));
    }
    @Test void expiredEnrichmentCannotEraseAFreshPositiveOrLowerTheCapturedThreshold() {
        ProviderVote fresh = new ProviderVote("api", ProviderVote.Status.POSITIVE, FailureReason.NONE, 10);
        ProviderVote expiredHosting = new ProviderVote("local.hosting", ProviderVote.Status.UNKNOWN, FailureReason.NO_EVIDENCE, 1,
                new DetectionDetails(Collections.singletonMap(DetectionDetails.Type.HOSTING, true), null, null, null, null, null, null), 2000, null, false);
        VpnResult mixed = new VpnResult("192.0.2.1", true); mixed.setValidUntil(2000); mixed.setVotes(Arrays.asList(fresh, expiredHosting));
        VpnResult current = LookupFreshness.vpn(mixed, 3000); assertTrue(current.isVpn()); assertFalse(current.getVotes().get(1).isVoting());
        assertTrue(current.getVotes().get(1).getDetails().getClassifications().isEmpty());
        ProviderVote expiredPositive = new ProviderVote("local.vpn", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1, DetectionDetails.empty(), 2000, null, true);
        mixed.setVotes(Arrays.asList(fresh, expiredPositive)); mixed.setPositiveThreshold(2);
        assertEquals(ProviderVote.Status.UNKNOWN, LookupFreshness.vpn(mixed, 3000).getStatus());
        assertEquals(2, LookupFreshness.vpn(mixed, 3000).getPositiveThreshold());
    }
}
