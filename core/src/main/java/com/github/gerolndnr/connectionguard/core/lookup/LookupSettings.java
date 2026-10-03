package com.github.gerolndnr.connectionguard.core.lookup;

public final class LookupSettings {
    public final long deadlineMillis;
    public final long httpTimeoutMillis;
    public final int workers;
    public final int queueCapacity;
    public final int maxInflight;
    public final int circuitFailures;
    public final long circuitPauseMillis;
    public LookupSettings(long deadlineMillis, long httpTimeoutMillis, int workers, int queueCapacity,
                          int maxInflight, int circuitFailures, long circuitPauseMillis) {
        if (deadlineMillis < 50 || deadlineMillis > 60000 || httpTimeoutMillis < 50
                || httpTimeoutMillis > deadlineMillis || workers < 1 || workers > 64
                || queueCapacity < 1 || queueCapacity > 4096 || maxInflight < 1 || maxInflight > 4096
                || circuitFailures < 1 || circuitPauseMillis < 50 || circuitPauseMillis > 3600000) {
            throw new IllegalArgumentException("Invalid lookup limits; see docs/OPERATIONS.md.");
        }
        this.deadlineMillis = deadlineMillis;
        this.httpTimeoutMillis = httpTimeoutMillis;
        this.workers = workers;
        this.queueCapacity = queueCapacity;
        this.maxInflight = maxInflight;
        this.circuitFailures = circuitFailures;
        this.circuitPauseMillis = circuitPauseMillis;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof LookupSettings)) return false;
        LookupSettings next = (LookupSettings) other;
        return deadlineMillis == next.deadlineMillis && httpTimeoutMillis == next.httpTimeoutMillis
                && workers == next.workers && queueCapacity == next.queueCapacity && maxInflight == next.maxInflight
                && circuitFailures == next.circuitFailures && circuitPauseMillis == next.circuitPauseMillis;
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(deadlineMillis, httpTimeoutMillis, workers, queueCapacity,
                maxInflight, circuitFailures, circuitPauseMillis);
    }
    public static LookupSettings defaults() { return new LookupSettings(5000, 2500, 8, 64, 128, 3, 30000); }
}
