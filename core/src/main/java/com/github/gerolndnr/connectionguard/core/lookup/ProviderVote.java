package com.github.gerolndnr.connectionguard.core.lookup;

public final class ProviderVote {
    public enum Status { POSITIVE, NEGATIVE, UNKNOWN }
    private final String provider;
    private final Status status;
    private final FailureReason reason;
    private final long durationMillis;
    private final DetectionDetails details;
    public ProviderVote(String provider, Status status, FailureReason reason, long durationMillis) {
        this(provider, status, reason, durationMillis, DetectionDetails.empty());
    }
    public ProviderVote(String provider, Status status, FailureReason reason, long durationMillis, DetectionDetails details) {
        this.provider = provider;
        this.status = status;
        this.reason = reason;
        this.durationMillis = durationMillis;
        this.details = details;
        validate();
    }
    public String getProvider() { return provider; }
    public Status getStatus() { return status; }
    public FailureReason getReason() { return reason; }
    public long getDurationMillis() { return durationMillis; }
    public DetectionDetails getDetails() { return details == null ? DetectionDetails.empty() : details; }
    public void validate() {
        if (provider == null || !provider.matches("[a-zA-Z0-9_#.-]{1,100}") || status == null || reason == null || durationMillis < 0
                || (status == Status.UNKNOWN) != (reason != FailureReason.NONE)) throw new IllegalArgumentException("Invalid provider vote.");
        getDetails().validate();
    }
}
