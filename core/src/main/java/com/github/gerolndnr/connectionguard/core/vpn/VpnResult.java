package com.github.gerolndnr.connectionguard.core.vpn;

import java.util.Optional;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;

public class VpnResult {
    private final String ipAddress;
    private String vpnProviderName;
    private boolean isVpn;
    private long cachedOn;
    private ProviderVote.Status status;
    private List<ProviderVote> votes = Collections.emptyList();
    private boolean fromCache;
    private DetectionDetails details;
    private FailureReason sourceReason = FailureReason.NONE;
    private long validUntil;
    private String sourceVersion;
    private int positiveThreshold = 1;

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
        sourceReason = FailureReason.NONE;
    }
    public ProviderVote.Status getStatus() { return status; }
    public void setStatus(ProviderVote.Status status) { this.status = status; this.isVpn = status == ProviderVote.Status.POSITIVE; this.sourceReason = status == ProviderVote.Status.UNKNOWN ? FailureReason.NO_EVIDENCE : FailureReason.NONE; }
    public void setUnknown(FailureReason reason) { if (reason == null || reason == FailureReason.NONE) throw new IllegalArgumentException("UNKNOWN needs a reason."); setStatus(ProviderVote.Status.UNKNOWN); sourceReason = reason; }
    public FailureReason getSourceReason() { return sourceReason == null ? FailureReason.NONE : sourceReason; }
    public long getValidUntil() { return validUntil; }
    public void setValidUntil(long until) { if (until < 0) throw new IllegalArgumentException("Invalid source expiry."); validUntil = until; }
    public String getSourceVersion() { return sourceVersion; }
    public int getPositiveThreshold() { return positiveThreshold; }
    public void setPositiveThreshold(int threshold) { if (threshold < 1 || threshold > 16) throw new IllegalArgumentException("Invalid positive threshold."); positiveThreshold = threshold; }
    public void setSourceVersion(String version) { if (version != null && !version.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source version."); sourceVersion = version; }
    public List<ProviderVote> getVotes() { return votes == null ? Collections.emptyList() : Collections.unmodifiableList(votes); }
    public void setVotes(List<ProviderVote> votes) { this.votes = Collections.unmodifiableList(new ArrayList<>(votes)); }
    public boolean isFromCache() { return fromCache; }
    public void setFromCache(boolean fromCache) { this.fromCache = fromCache; }
    public DetectionDetails getDetails() { return details == null ? DetectionDetails.empty() : details; }
    public void setDetails(DetectionDetails details) { if (details != null) details.validate(); this.details = details; }
}
