package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObserver;

/**
 * The built-in (cloud) observer can also receive the name the player reported at login. Addon observers never do:
 * the public {@link DecisionObservation} contract stays without names.
 */
public interface NamedDecisionObserver extends DecisionObserver {
    /** {@code reportedName} is null when no name was reported; it is not authentication proof. */
    void onDecision(DecisionObservation observation, String reportedName) throws Exception;
    @Override default void onDecision(DecisionObservation observation) throws Exception { onDecision(observation, null); }
}
