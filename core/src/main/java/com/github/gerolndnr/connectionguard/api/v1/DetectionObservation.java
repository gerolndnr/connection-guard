package com.github.gerolndnr.connectionguard.api.v1;

/** Immutable result of one selected detection source. No platform/player object or exception text. */
public final class DetectionObservation {
    public enum Status { POSITIVE, NEGATIVE, UNKNOWN }
    public enum Reason { NONE, TIMEOUT, RATE_LIMIT, HTTP_ERROR, AUTHENTICATION, INVALID_RESPONSE,
        NETWORK, OVERLOADED, CIRCUIT_OPEN, BUDGET_EXHAUSTED, NO_PROVIDER, CACHE_ERROR, CANCELLED, NO_EVIDENCE, STALE_DATA }
    private final Status status;
    private final Reason reason;
    private final DetectionMetadata metadata;
    private final long validUntil;
    private final String sourceVersion;
    public DetectionObservation(Status status, Reason reason, DetectionMetadata metadata, long validUntil, String sourceVersion) {
        if (status == null || reason == null || (status == Status.UNKNOWN) != (reason != Reason.NONE)
                || validUntil < 0 || sourceVersion != null && !sourceVersion.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid versioned provider observation.");
        this.status = status; this.reason = reason; this.metadata = java.util.Objects.requireNonNull(metadata);
        this.validUntil = validUntil; this.sourceVersion = sourceVersion;
    }
    public static DetectionObservation positive(DetectionMetadata metadata) { return new DetectionObservation(Status.POSITIVE, Reason.NONE, metadata, 0, null); }
    public static DetectionObservation negative(DetectionMetadata metadata) { return new DetectionObservation(Status.NEGATIVE, Reason.NONE, metadata, 0, null); }
    public static DetectionObservation unknown(Reason reason) { return new DetectionObservation(Status.UNKNOWN, reason, DetectionMetadata.empty(), 0, null); }
    public Status getStatus() { return status; }
    public Reason getReason() { return reason; }
    public DetectionMetadata getMetadata() { return metadata; }
    public long getValidUntil() { return validUntil; }
    public String getSourceVersion() { return sourceVersion; }
}
