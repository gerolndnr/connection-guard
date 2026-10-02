package com.github.gerolndnr.connectionguard.core.lookup;

public final class ProviderVote {
    public enum Status { POSITIVE, NEGATIVE, UNKNOWN }
    private final String provider;
    private final Status status;
    private final FailureReason reason;
    private final long durationMillis;
    private final DetectionDetails details;
    private final long validUntil;
    private final String sourceVersion;
    public ProviderVote(String provider, Status status, FailureReason reason, long durationMillis) {
        this(provider, status, reason, durationMillis, DetectionDetails.empty());
    }
    public ProviderVote(String provider, Status status, FailureReason reason, long durationMillis, DetectionDetails details) {
        this(provider, status, reason, durationMillis, details, 0, null);
    }
    public ProviderVote(String provider, Status status, FailureReason reason, long durationMillis, DetectionDetails details, long validUntil, String sourceVersion) {
        this.provider = provider;
        this.status = status;
        this.reason = reason;
        this.durationMillis = durationMillis;
        this.details = details;
        this.validUntil = validUntil; this.sourceVersion = sourceVersion;
        validate();
    }
    public String getProvider() { return provider; }
    public Status getStatus() { return status; }
    public FailureReason getReason() { return reason; }
    public long getDurationMillis() { return durationMillis; }
    public DetectionDetails getDetails() { return details == null ? DetectionDetails.empty() : details; }
    public long getValidUntil() { return validUntil; }
    public String getSourceVersion() { return sourceVersion; }
    public boolean isFresh(long now) { return validUntil == 0 || now < validUntil; }
    public void validate() {
        if (provider == null || !provider.matches("[a-zA-Z0-9_#.-]{1,100}") || status == null || reason == null || durationMillis < 0
                || (status == Status.UNKNOWN) != (reason != FailureReason.NONE) || validUntil < 0
                || (sourceVersion != null && !sourceVersion.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("Invalid provider vote.");
        getDetails().validate();
    }
}
