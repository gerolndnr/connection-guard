package com.github.gerolndnr.connectionguard.core.challenge;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ChallengeSessionTest {
    final Object owner = new Object();
    final AtomicLong clock = new AtomicLong(123);
    ChallengeSession session(java.util.function.BooleanSupplier live, int attempts) {
        return new ChallengeSession(owner, live, 20, attempts, clock::get, "012345");
    }
    @Test void onlyActualOwningInputPassesOnceAndClearsDisplay() {
        ChallengeSession ticket = session(() -> true, 3);
        assertEquals("012345", ticket.displayText(owner));
        assertEquals(ChallengeSession.Answer.NOT_CURRENT, ticket.answer(new Object(), "012345"));
        assertFalse(ticket.isPassed(owner));
        assertEquals(ChallengeSession.Answer.ACCEPTED, ticket.answer(owner, "012345"));
        assertTrue(ticket.isPassed(owner));
        assertFalse(ticket.isPassed(new Object()));
        assertNull(ticket.displayText(owner));
        assertEquals(ChallengeSession.Answer.TERMINAL, ticket.answer(owner, "012345"));
    }
    @Test void incorrectAndOversizedInputConsumesFiniteAttempts() {
        ChallengeSession ticket = session(() -> true, 3);
        for (String input : new String[] {"01234６", new String(new char[100000]), " 012345"})
            assertEquals(ChallengeSession.Answer.INCORRECT, ticket.answer(owner, input));
        assertEquals(ChallengeSession.State.REJECTED, ticket.state());
        assertEquals(ChallengeSession.Answer.TERMINAL, ticket.answer(owner, "012345"));
        assertFalse(ticket.isPassed(owner));
    }
    @Test void exactDeadlineAndLatePassNeverAuthorize() {
        ChallengeSession ticket = session(() -> true, 3);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
        assertEquals(ChallengeSession.Answer.NOT_CURRENT, ticket.answer(owner, "012345"));
        assertEquals(ChallengeSession.State.EXPIRED, ticket.state());
        assertEquals(0, ticket.remainingMillis(owner));
    }
    @Test void passedReceiptStillRequiresLiveConnectionAndFreshDeadline() {
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        ChallengeSession ticket = session(active::get, 3);
        assertEquals(ChallengeSession.Answer.ACCEPTED, ticket.answer(owner, "012345"));
        active.set(false);
        assertFalse(ticket.isPassed(owner));
        assertEquals(ChallengeSession.State.INVALIDATED, ticket.state());
        ticket = session(() -> true, 3);
        assertEquals(ChallengeSession.Answer.ACCEPTED, ticket.answer(owner, "012345"));
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
        assertFalse(ticket.isPassed(owner));
        assertEquals(ChallengeSession.State.EXPIRED, ticket.state());
    }
    @Test void policyChangeDuringAnswerComparisonCannotPublishPass() {
        AtomicInteger reads = new AtomicInteger();
        ChallengeSession ticket = session(() -> reads.incrementAndGet() == 1, 3);
        assertEquals(ChallengeSession.Answer.NOT_CURRENT, ticket.answer(owner, "012345"));
        assertEquals(ChallengeSession.State.INVALIDATED, ticket.state());
    }
    @Test void unavailableProbeAndShutdownFailClosed() {
        ChallengeSession ticket = session(() -> { throw new NoClassDefFoundError("Fixture unavailable"); }, 3);
        assertNull(ticket.displayText(owner));
        assertEquals(ChallengeSession.State.INVALIDATED, ticket.state());
        ticket = session(() -> true, 3); ticket.close();
        assertEquals(ChallengeSession.Answer.TERMINAL, ticket.answer(owner, "012345"));
        assertFalse(ticket.isPassed(owner));
    }
    @Test void monotonicClockRolloverDoesNotExpireEarlyOrExtendDeadline() {
        clock.set(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(10));
        ChallengeSession ticket = session(() -> true, 3);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(19));
        assertTrue(ticket.remainingMillis(owner) > 0);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertEquals(ChallengeSession.State.EXPIRED, ticket.state());
    }
    @Test void concurrentCorrectAnswersHaveOnlyOneWinner() throws Exception {
        ChallengeSession ticket = session(() -> true, 3);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<ChallengeSession.Answer> one = threads.submit(() -> ticket.answer(owner, "012345"));
            java.util.concurrent.Future<ChallengeSession.Answer> two = threads.submit(() -> ticket.answer(owner, "012345"));
            assertNotEquals(one.get(2, java.util.concurrent.TimeUnit.SECONDS), two.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ticket.isPassed(owner));
        } finally { threads.shutdownNow(); }
    }
    @Test void invalidBoundsAndMistypedConfigurationCannotDisableChallenge() {
        assertThrows(IllegalArgumentException.class, () -> new ChallengeSession(owner, () -> true, 120001, 3));
        assertThrows(IllegalArgumentException.class, () -> new ChallengeSession(owner, () -> true, 20, 9));
        for (String[] entry : new String[][] {{"enabled", "yes"}, {"enable", "false"}, {"timeout-seconds", "121"},
                {"maximum-attempts", "0"}, {"maximum-sessions", "4097"}, {"timeout-seconds", "-1"}}) {
            Properties values = new Properties(); values.setProperty(entry[0], entry[1]);
            assertThrows(IllegalArgumentException.class, () -> ChallengeSettings.read(values));
        }
        assertFalse(ChallengeSettings.read(new Properties()).enabled);
    }
}
