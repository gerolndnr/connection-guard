package com.github.gerolndnr.connectionguard.core.lookup;

public final class ProviderVote {
    public enum Status { POSITIVE, NEGATIVE, UNKNOWN }
    private final String provider;
    private final Status status;
    private final FailureReason reason;
    private final long durationMillis;
    public ProviderVote(String provider, Status status, FailureReason reason, long durationMillis) {
        this.provider = provider;
        this.status = status;
        this.reason = reason;
        this.durationMillis = durationMillis;
    }
    public String getProvider() { return provider; }
    public Status getStatus() { return status; }
    public FailureReason getReason() { return reason; }
    public long getDurationMillis() { return durationMillis; }
}
