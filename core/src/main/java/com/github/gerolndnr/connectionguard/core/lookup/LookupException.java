package com.github.gerolndnr.connectionguard.core.lookup;

/** Intentionally omits URLs, request bodies, IPs, keys and nested exception messages. */
public final class LookupException extends RuntimeException {
    private final FailureReason reason;
    private final long retryAfterMillis;
    public LookupException(FailureReason reason) { this(reason, 0); }
    public LookupException(FailureReason reason, long retryAfterMillis) {
        super(reason.name());
        this.reason = reason;
        this.retryAfterMillis = retryAfterMillis;
    }
    public FailureReason getReason() { return reason; }
    public long getRetryAfterMillis() { return retryAfterMillis; }
    public static FailureReason reason(Throwable error) {
        while (error != null) {
            if (error instanceof LookupException) return ((LookupException) error).getReason();
            if (error instanceof java.util.concurrent.TimeoutException) return FailureReason.TIMEOUT;
            if (error instanceof java.util.concurrent.CancellationException) return FailureReason.CANCELLED;
            error = error.getCause();
        }
        return FailureReason.NETWORK;
    }
}
