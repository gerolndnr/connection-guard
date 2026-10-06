package com.github.gerolndnr.connectionguard.core.lookup;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderHealthRecoveryTest {
    @Test void configuredPauseHasOneHalfOpenProbeWithoutHiddenShortening() {
        ProviderHealth h = new ProviderHealth(); LookupSettings settings = new LookupSettings(5000, 1000, 8, 64, 128, 1, 30000);
        long now = System.currentTimeMillis(); assertEquals(FailureReason.NONE, h.reserve(now));
        h.record(FailureReason.NETWORK, new LookupException(FailureReason.NETWORK), settings);
        assertEquals(FailureReason.CIRCUIT_OPEN, h.reserve(now + 100));
        assertEquals(FailureReason.CIRCUIT_OPEN, h.reserve(System.currentTimeMillis() + 1100));
        assertEquals(FailureReason.NONE, h.reserve(System.currentTimeMillis() + 31000));
        assertEquals(FailureReason.CIRCUIT_OPEN, h.reserve(System.currentTimeMillis() + 31000));
        h.record(FailureReason.NONE, null, settings); assertEquals(FailureReason.NONE, h.reserve(System.currentTimeMillis() + 31000));
    }
    @Test void retryAfterPreventsEarly429ProbeAndQuotaIsVisible() {
        ProviderHealth h = new ProviderHealth(); h.reserve(System.currentTimeMillis());
        h.record(FailureReason.RATE_LIMIT, new LookupException(FailureReason.RATE_LIMIT, 60000), LookupSettings.defaults());
        assertEquals(FailureReason.CIRCUIT_OPEN, h.reserve(System.currentTimeMillis() + 2000));
        h.resetFailures(); h.budgets(1, 0);
        assertEquals(FailureReason.BUDGET_EXHAUSTED, h.reserve(System.currentTimeMillis()));
        assertEquals(FailureReason.BUDGET_EXHAUSTED, h.snapshot().lastReason);
    }
}
