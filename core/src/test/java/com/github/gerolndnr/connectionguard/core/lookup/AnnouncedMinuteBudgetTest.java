package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.http.AnnouncedLimits;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncedMinuteBudgetTest {
    @AfterEach void clear() { AnnouncedLimits.clear(); }

    private static int reservations(ProviderHealth health) {
        long now = System.currentTimeMillis(); int granted = 0;
        while (health.reserve(now) == FailureReason.NONE && granted < 5000) granted++;
        return granted;
    }

    @Test void usesTheFallbackUntilTheServiceAnnouncesALimit() {
        ProviderHealth health = new ProviderHealth(); health.budgets(0, 60, "zowi");
        assertEquals(60, reservations(health));
    }

    @Test void followsEightyPercentOfTheAnnouncedLimit() {
        AnnouncedLimits.set("zowi", 250);
        ProviderHealth health = new ProviderHealth(); health.budgets(0, 60, "zowi");
        assertEquals(200, health.effectiveMinuteBudget());
        assertEquals(200, reservations(health));
    }

    @Test void aFixedBudgetIgnoresAnnouncements() {
        AnnouncedLimits.set("zowi", 250);
        ProviderHealth health = new ProviderHealth(); health.budgets(0, 60);
        assertEquals(60, reservations(health));
    }

    @Test void anAnnouncementIsCappedAndNeverZero() {
        AnnouncedLimits.set("zowi", 1_000_000);
        ProviderHealth health = new ProviderHealth(); health.budgets(0, 60, "zowi");
        assertEquals(AnnouncedLimits.MAX * 4 / 5, health.effectiveMinuteBudget());
        AnnouncedLimits.set("zowi", 1);
        assertEquals(1, health.effectiveMinuteBudget());
    }

    @Test void oneTimeoutDoesNotPauseTheSourceButThreeInARowDo() {
        ProviderHealth health = new ProviderHealth(); LookupSettings settings = LookupSettings.defaults();
        health.record(FailureReason.TIMEOUT, null, settings);
        assertEquals(FailureReason.NONE, health.reserve(System.currentTimeMillis()));
        health.record(FailureReason.NONE, null, settings);
        health.record(FailureReason.TIMEOUT, null, settings); health.record(FailureReason.TIMEOUT, null, settings);
        assertEquals(FailureReason.NONE, health.reserve(System.currentTimeMillis()));
        health.record(FailureReason.TIMEOUT, null, settings);
        assertEquals(FailureReason.CIRCUIT_OPEN, health.reserve(System.currentTimeMillis()));
        ProviderHealth limited = new ProviderHealth(); limited.record(FailureReason.RATE_LIMIT, null, settings);
        assertEquals(FailureReason.CIRCUIT_OPEN, limited.reserve(System.currentTimeMillis()));
    }
}
