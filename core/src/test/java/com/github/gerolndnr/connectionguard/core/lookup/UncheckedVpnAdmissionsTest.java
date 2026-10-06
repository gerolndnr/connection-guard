package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UncheckedVpnAdmissionsTest {
    private static VpnResult unknown() {
        VpnResult result = new VpnResult("192.0.2.41", false);
        result.setUnknown(FailureReason.BUDGET_EXHAUSTED);
        result.setVotes(Arrays.asList(
                new ProviderVote("first", ProviderVote.Status.UNKNOWN, FailureReason.BUDGET_EXHAUSTED, 0),
                new ProviderVote("second", ProviderVote.Status.UNKNOWN, FailureReason.CIRCUIT_OPEN, 0)));
        return result;
    }
    private static DecisionCapture capture(Platform platform) {
        return DecisionCapture.begin(platform, Phase.LOGIN, "192.0.2.41", null, IdentityTrust.UNTRUSTED);
    }
    private static void facts(DecisionCapture capture, VpnResult vpn, boolean exempt) {
        capture.facts(vpn, new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0), exempt, true, System.currentTimeMillis());
    }
    @Test void periodicWindowsReportAll52UncheckedAdmissionsThenOnlyTheNextWindow() {
        AtomicLong time = new AtomicLong(1000); List<String> lines = new ArrayList<>();
        UncheckedVpnAdmissions tracker = new UncheckedVpnAdmissions(time::get);
        tracker.start(lines::add);
        try {
            for (int i = 0; i < 52; i++) tracker.allowed(FailureReason.BUDGET_EXHAUSTED);
            time.addAndGet(UncheckedVpnAdmissions.SUMMARY_MILLIS); tracker.summarize();
            assertEquals(1, lines.size()); assertTrue(lines.get(0).contains("52 logins"));
            assertTrue(lines.get(0).contains("last 300 seconds")); assertTrue(lines.get(0).contains("BUDGET_EXHAUSTED=52"));
            assertFalse(lines.get(0).contains("192.0.2.41"));
            assertEquals(52, tracker.snapshot().total); assertEquals(0, tracker.snapshot().sinceSummary);
            tracker.summarize(); assertEquals(1, lines.size(), "No empty/repeated warning");
            tracker.allowed(FailureReason.CIRCUIT_OPEN); time.addAndGet(UncheckedVpnAdmissions.SUMMARY_MILLIS); tracker.summarize();
            assertTrue(lines.get(1).contains("1 logins")); assertTrue(lines.get(1).contains("CIRCUIT_OPEN=1"));
            assertTrue(lines.get(1).contains("total=53")); assertFalse(lines.get(1).contains("BUDGET_EXHAUSTED"));
            assertEquals(52L, tracker.snapshot().reasons.get(FailureReason.BUDGET_EXHAUSTED));
            assertThrows(UnsupportedOperationException.class, () -> tracker.snapshot().reasons.clear());
        } finally { tracker.close(); }
    }
    @Test void shutdownReportsFinalPartialWindowExactlyOnce() {
        AtomicLong time = new AtomicLong(0); List<String> lines = new ArrayList<>();
        UncheckedVpnAdmissions tracker = new UncheckedVpnAdmissions(time::get); tracker.start(lines::add);
        tracker.allowed(FailureReason.TIMEOUT); time.set(20_000); tracker.close(); tracker.close();
        assertEquals(1, lines.size()); assertTrue(lines.get(0).contains("last 20 seconds"));
        assertEquals(1, tracker.snapshot().total);
    }
    @Test void concurrentLoginsAreCountedRatherThanCoalescedProviderRequests() throws Exception {
        UncheckedVpnAdmissions tracker = new UncheckedVpnAdmissions(); ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 1000; i++) jobs.add(pool.submit(() -> tracker.allowed(FailureReason.BUDGET_EXHAUSTED)));
            for (Future<?> job : jobs) job.get(2, TimeUnit.SECONDS);
            assertEquals(1000, tracker.snapshot().total); assertEquals(1000L, tracker.snapshot().reasons.get(FailureReason.BUDGET_EXHAUSTED));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tracker.close(); }
    }
    @Test void allNativePlatformsCountUnknownAllowedEvenWithCloudAndObserversDisabled() {
        DecisionObservers.closeAll(); ConnectionGuard.applySettings(GuardSettings.defaults());
        long before = ConnectionGuard.uncheckedVpnAdmissions().snapshot().total;
        for (Platform platform : Arrays.asList(Platform.VELOCITY, Platform.BUNGEE, Platform.BUKKIT)) {
            DecisionCapture capture = capture(platform); facts(capture, unknown(), false); capture.close(); capture.close();
        }
        assertEquals(before + 3, ConnectionGuard.uncheckedVpnAdmissions().snapshot().total);
        assertTrue(com.github.gerolndnr.connectionguard.core.commands.OperationsCommands.doctor().stream()
                .anyMatch(line -> line.contains("uncheckedAllowed=" + (before + 3))));
        assertTrue(com.github.gerolndnr.connectionguard.core.cloud.CloudSync.describeLines().stream()
                .anyMatch(line -> line.contains("uncheckedAllowed=" + (before + 3))));
    }
    @Test void deniesErrorsExemptionsAndSuccessfulFallbacksNeverInflateTheCounter() {
        long before = ConnectionGuard.uncheckedVpnAdmissions().snapshot().total;
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, unknown(), false); capture.denied(Reason.LOOKUP_UNAVAILABLE); }
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, unknown(), false); capture.error(); }
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, unknown(), true); }
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { capture.overload(true); }
        VpnResult fallback = new VpnResult("192.0.2.41", false);
        fallback.setVotes(Arrays.asList(new ProviderVote("first", ProviderVote.Status.UNKNOWN, FailureReason.BUDGET_EXHAUSTED, 0),
                new ProviderVote("second", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 0)));
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, fallback, false); }
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, new VpnResult("192.0.2.41", true), false); }
        assertEquals(before, ConnectionGuard.uncheckedVpnAdmissions().snapshot().total);
    }
    @Test void unknownLookupQueriesAloneAndReloadNeverChangeLoginCounts() {
        long before = ConnectionGuard.uncheckedVpnAdmissions().snapshot().total;
        assertEquals(FailureReason.BUDGET_EXHAUSTED, UncheckedVpnAdmissions.unresolved(unknown(), false));
        assertNull(UncheckedVpnAdmissions.unresolved(unknown(), true));
        ConnectionGuard.applySettings(GuardSettings.defaults());
        assertEquals(before, ConnectionGuard.uncheckedVpnAdmissions().snapshot().total);
    }
    @Test void loggerFailureCannotThrowIntoAdmissionOrFutureReporting() {
        UncheckedVpnAdmissions tracker = new UncheckedVpnAdmissions(); tracker.start(line -> { throw new IllegalStateException("secret"); });
        try { tracker.allowed(FailureReason.NETWORK); assertDoesNotThrow(tracker::summarize); assertEquals(1, tracker.snapshot().total); }
        finally { assertDoesNotThrow(tracker::close); }
    }
}
