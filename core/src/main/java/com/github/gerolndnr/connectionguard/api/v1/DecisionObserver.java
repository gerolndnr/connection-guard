package com.github.gerolndnr.connectionguard.api.v1;

/** Receives immutable observations on a bounded guard worker, never a platform/player thread. */
@FunctionalInterface
public interface DecisionObserver {
    void onDecision(DecisionObservation observation) throws Exception;
}
