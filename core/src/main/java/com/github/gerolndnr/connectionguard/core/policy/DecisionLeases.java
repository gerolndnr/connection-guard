package com.github.gerolndnr.connectionguard.core.policy;

/** Scalar lifetime guard; retains no players or futures. */
public final class DecisionLeases {
    private long generation, active;
    public final class Lease implements AutoCloseable {
        private final long owner;
        private boolean closed;
        private Lease(long owner) { this.owner = owner; }
        @Override public void close() { synchronized (DecisionLeases.this) {
            if (closed) return; closed = true; if (owner == generation) active--;
        } }
    }
    public synchronized Lease acquire() { active = Math.addExact(active, 1); return new Lease(generation); }
    public synchronized long active() { return active; }
    public synchronized void requireIdle() { if (active != 0) throw new IllegalStateException("Wait for final login decisions before changing policy."); }
    public synchronized void reset() { generation++; active = 0; }
}
