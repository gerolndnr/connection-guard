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
    @Test void missingLocalHintsNeverHideALaterQuotaFailureInTheSameUnknownLogin() {
        VpnResult result=new VpnResult("192.0.2.41",false);result.setUnknown(FailureReason.NO_EVIDENCE);
        result.setVotes(Arrays.asList(new ProviderVote("local",ProviderVote.Status.UNKNOWN,FailureReason.NO_EVIDENCE,0),
                new ProviderVote("api",ProviderVote.Status.UNKNOWN,FailureReason.BUDGET_EXHAUSTED,0)));
        assertEquals(FailureReason.BUDGET_EXHAUSTED,UncheckedVpnAdmissions.unresolved(result,false));
        result.setStatus(ProviderVote.Status.NEGATIVE);assertNull(UncheckedVpnAdmissions.unresolved(result,false));
    }
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
    private static ProviderVote vote(String provider, FailureReason reason, Boolean hosting) {
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        if (hosting != null) types.put(DetectionDetails.Type.HOSTING, hosting);
        return new ProviderVote(provider, ProviderVote.Status.UNKNOWN, reason, 0, new DetectionDetails(types, null, null, null, null, null, null));
    }
    @Test void signalsNameEachInconclusiveVotingSourceOnceWithoutAddresses() {
        VpnResult result = new VpnResult("2001:db8::7", false); result.setUnknown(FailureReason.NO_EVIDENCE);
        result.setVotes(Arrays.asList(vote("ZowiVpnProvider-0", FailureReason.NO_EVIDENCE, true),
                vote("blackboxvpnprovider-0", FailureReason.NO_EVIDENCE, null),
                vote("proxycheckvpnprovider-0", FailureReason.TIMEOUT, null), vote("proxycheckvpnprovider-0", FailureReason.NETWORK, null),
                vote("Custom.API#192.0.2.1", FailureReason.NETWORK, false),
                new ProviderVote("ipquery", ProviderVote.Status.UNKNOWN, FailureReason.NETWORK, 0, null, 0, null, false),
                new ProviderVote("ipcheck", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 0)));
        assertEquals(Arrays.asList("zowivpnprovider-0.hosting", "blackboxvpnprovider-0.no_evidence", "proxycheckvpnprovider-0.failed",
                "custom_api_192_0_2_1.failed"), UncheckedVpnAdmissions.signals(result, false));
        for (String key : UncheckedVpnAdmissions.signals(result, false))
            assertTrue(key.matches("[a-z0-9_-]{1,48}\\.(hosting|no_evidence|failed)"), key);
        assertEquals(Collections.emptyList(), UncheckedVpnAdmissions.signals(result, true));
        result.setStatus(ProviderVote.Status.NEGATIVE); assertEquals(Collections.emptyList(), UncheckedVpnAdmissions.signals(result, false));
        assertEquals("ipv6", UncheckedVpnAdmissions.family("2001:db8::7")); assertEquals("ipv4", UncheckedVpnAdmissions.family("192.0.2.41"));
    }
    @Test void signalKeysAreCappedAndFamiliesCountedPerAdmission() {
        UncheckedVpnAdmissions tracker = new UncheckedVpnAdmissions();
        for (int i = 0; i < 40; i++) tracker.allowed(FailureReason.NO_EVIDENCE, Collections.singletonList("s" + i + ".failed"), i % 4 == 0 ? "ipv6" : "ipv4");
        tracker.allowed(FailureReason.NO_EVIDENCE, Collections.singletonList("s0.failed"), "ipv4");
        tracker.allowed(FailureReason.NONE, Collections.singletonList("s1.failed"), "ipv4");
        UncheckedVpnAdmissions.Snapshot snapshot = tracker.snapshot();
        assertEquals(UncheckedVpnAdmissions.MAX_SIGNALS, snapshot.signals.size());
        assertEquals(9L, snapshot.signals.get("other")); assertEquals(2L, snapshot.signals.get("s0.failed"));
        assertEquals(10L, snapshot.families.get("ipv6")); assertEquals(31L, snapshot.families.get("ipv4"));
        assertTrue(snapshot.describe().contains("signals=")); tracker.close();
    }
    @Test void nativeLoginsRecordTheirSignalsAndFamily() {
        DecisionObservers.closeAll(); ConnectionGuard.applySettings(GuardSettings.defaults());
        UncheckedVpnAdmissions.Snapshot before = ConnectionGuard.uncheckedVpnAdmissions().snapshot();
        VpnResult result = new VpnResult("192.0.2.41", false); result.setUnknown(FailureReason.NO_EVIDENCE);
        result.setVotes(Collections.singletonList(vote("zowivpnprovider-0", FailureReason.NO_EVIDENCE, true)));
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, result, false); }
        try (DecisionCapture capture = capture(Platform.VELOCITY)) { facts(capture, result, true); }
        UncheckedVpnAdmissions.Snapshot after = ConnectionGuard.uncheckedVpnAdmissions().snapshot();
        assertEquals(before.signals.getOrDefault("zowivpnprovider-0.hosting", 0L) + 1, after.signals.get("zowivpnprovider-0.hosting"));
        assertEquals(before.families.getOrDefault("ipv4", 0L) + 1, after.families.get("ipv4"));
    }
    @Test void loggerFailureCannotThrowIntoAdmissionOrFutureReporting() {
        UncheckedVpnAdmissions tracker = new UncheckedVpnAdmissions(); tracker.start(line -> { throw new IllegalStateException("secret"); });
        try { tracker.allowed(FailureReason.NETWORK); assertDoesNotThrow(tracker::summarize); assertEquals(1, tracker.snapshot().total); }
        finally { assertDoesNotThrow(tracker::close); }
    }
}
