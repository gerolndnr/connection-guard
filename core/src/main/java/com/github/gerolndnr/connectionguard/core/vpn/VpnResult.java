package com.github.gerolndnr.connectionguard.core.vpn;

import java.util.Optional;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;

public class VpnResult {
    private final String ipAddress;
    private String vpnProviderName;
    private boolean isVpn;
    private long cachedOn;
    private ProviderVote.Status status;
    private List<ProviderVote> votes = Collections.emptyList();
    private boolean fromCache;
    private DetectionDetails details;

    public VpnResult(String ipAddress, boolean isVpn) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
        this.status = isVpn ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE;
        this.vpnProviderName = null;
    }

    public VpnResult(String ipAddress, boolean isVpn, Optional<String> vpnProviderName) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
        this.status = isVpn ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE;
        this.vpnProviderName = vpnProviderName.orElse(null);
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isVpn() {
        return isVpn;
    }

    public Optional<String> getVpnProviderName() {
        return Optional.ofNullable(vpnProviderName);
    }

    public long getCachedOn() {
        return cachedOn;
    }

    public void setCachedOn(long cachedOn) {
        this.cachedOn = cachedOn;
    }

    public void setVpn(boolean vpn) {
        isVpn = vpn;
        status = vpn ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE;
    }
    public ProviderVote.Status getStatus() { return status; }
    public void setStatus(ProviderVote.Status status) { this.status = status; this.isVpn = status == ProviderVote.Status.POSITIVE; }
    public List<ProviderVote> getVotes() { return votes == null ? Collections.emptyList() : Collections.unmodifiableList(votes); }
    public void setVotes(List<ProviderVote> votes) { this.votes = Collections.unmodifiableList(new ArrayList<>(votes)); }
    public boolean isFromCache() { return fromCache; }
    public void setFromCache(boolean fromCache) { this.fromCache = fromCache; }
    public DetectionDetails getDetails() { return details == null ? DetectionDetails.empty() : details; }
    public void setDetails(DetectionDetails details) { if (details != null) details.validate(); this.details = details; }
}
