package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PolicyObservationBoundsTest {
    @TempDir Path directory;
    private final AtomicLong monotonic = new AtomicLong(), wall = new AtomicLong(1_800_000_000_000L);
    private PolicyObservationCapture capture;
    private static final String IP = "198.51.100.42";
    @BeforeEach void setup() { capture = new PolicyObservationCapture(monotonic::get, wall::get); capture.configure(directory); }
    @AfterEach void cleanup() { capture.close(); }
    private VpnResult vpn(ProviderVote.Status status) {
        VpnResult value = new VpnResult(IP, status == ProviderVote.Status.POSITIVE);
        if (status == ProviderVote.Status.UNKNOWN) value.setUnknown(FailureReason.BUDGET_EXHAUSTED);
        value.setVotes(Collections.singletonList(new ProviderVote("fixture", status, status == ProviderVote.Status.UNKNOWN ? FailureReason.BUDGET_EXHAUSTED : FailureReason.NONE, 1)));
        return value;
    }
    private void record(VpnResult vpn, boolean ordered) {
        capture.record(Collections.emptyList(), IP, null, false, false, false, vpn,
                new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 0), null, wall.get(), ordered);
    }
    private PolicyReplay.Cases read() throws Exception {
        try (InputStream input = Files.newInputStream(directory.resolve("policy/observations.json"))) { return PolicyReplay.readCases(input); }
    }
    @Test void sampleDurationAndSizeAreHardBoundedWithNoAutomaticSave() throws Exception {
        capture.start(1000);
        for (int i = 0; i < 50; i++) record(vpn(ProviderVote.Status.NEGATIVE), true);
        assertEquals(32, capture.status().samples); assertEquals(PolicyObservationCapture.State.SAMPLE_LIMIT, capture.status().state);
        assertFalse(Files.exists(directory.resolve("policy")));
        capture.stop(); assertEquals(32, read().cases.size());
        assertTrue(Files.size(directory.resolve("policy/observations.json")) <= PolicyObservationCapture.MAX_BYTES);
        assertThrows(IllegalStateException.class, () -> capture.start(1000));
        capture.clear(); capture.start(1000); record(vpn(ProviderVote.Status.NEGATIVE), true);
        monotonic.set(TimeUnit.MILLISECONDS.toNanos(1001)); record(vpn(ProviderVote.Status.POSITIVE), true);
        assertEquals(PolicyObservationCapture.State.EXPIRED, capture.status().state); assertEquals(1, capture.status().samples);
        capture.stop(); assertEquals(1, read().cases.size());
    }
    @Test void captureCopiesMutableResultsAndPreservesFailoverReasonQuorumAndExpiry() throws Exception {
        capture.start(1000);
        VpnResult negative = vpn(ProviderVote.Status.NEGATIVE);
        negative.setVotes(Arrays.asList(new ProviderVote("failed", ProviderVote.Status.UNKNOWN, FailureReason.TIMEOUT, 1500),
                new ProviderVote("fixture", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 1)));
        record(negative, true); negative.setVpn(true); negative.setVotes(Collections.emptyList());
        VpnResult unavailable = vpn(ProviderVote.Status.UNKNOWN); record(unavailable, true);
        VpnResult quorum = vpn(ProviderVote.Status.UNKNOWN); quorum.setPositiveThreshold(2);
        quorum.setVotes(Arrays.asList(new ProviderVote("positive", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1),
                new ProviderVote("failed", ProviderVote.Status.UNKNOWN, FailureReason.TIMEOUT, 1500)));
        record(quorum, false);
        capture.stop(); PolicyReplay.Cases saved = read();
        PolicyReplay.Snapshot policy = new PolicyReplay.Snapshot(GuardSettings.defaults(), Collections.emptyList());
        assertEquals(ProviderVote.Status.NEGATIVE, saved.cases.get(0).evaluate(policy, wall.get()).vpn.getStatus());
        assertEquals(FailureReason.BUDGET_EXHAUSTED, saved.cases.get(1).evaluate(policy, wall.get()).vpn.getSourceReason());
        assertEquals(ProviderVote.Status.UNKNOWN, saved.cases.get(2).evaluate(policy, wall.get()).vpn.getStatus());
        assertEquals(2, saved.cases.get(2).evaluate(policy, wall.get()).vpn.getPositiveThreshold());
    }
    @Test void historicalReplayUsesExactRecordedTimeAndNowExpiresTheSameFacts() throws Exception {
        capture.start(1000);
        VpnResult value = vpn(ProviderVote.Status.POSITIVE); value.setValidUntil(wall.get() + 10);
        value.setVotes(Collections.singletonList(new ProviderVote("fixture", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1,
                DetectionDetails.empty().withDataAsOf(wall.get()), wall.get() + 10, null)));
        record(value, true); capture.stop(); PolicyReplay.Case saved = read().cases.get(0);
        Map<String,Object> fields = new HashMap<>(); fields.put("behavior.vpn.kick-player", true); fields.put("failure-policy.vpn", "CLOSED");
        PolicyReplay.Snapshot policy = new PolicyReplay.Snapshot(GuardSettings.read(fields::get, Collections.emptyList()), Collections.emptyList());
        assertEquals(Reason.VPN_FLAG, saved.evaluate(policy, saved.observedAt).denial);
        assertEquals(Reason.LOOKUP_UNAVAILABLE, saved.evaluate(policy, saved.observedAt + 10).denial);
        assertEquals(Long.valueOf(wall.get()), saved.sources(saved.observedAt).get(0).getDetails().getDataAsOf());
    }
    @Test void disabledAndExemptGeoInputsAreCapturedWithoutInventingCountryOrFailure() throws Exception {
        capture.start(1000);
        for (boolean exempt : Arrays.asList(false, true)) capture.record(Collections.emptyList(), IP, null, false, false, exempt,
                vpn(ProviderVote.Status.POSITIVE), new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0), null, wall.get(), true);
        assertEquals(2, capture.status().samples); assertEquals(0, capture.status().skippedUnsupported);
        capture.stop(); PolicyReplay.Cases saved = read();
        for (PolicyReplay.Case entry : saved.cases) {
            ConnectionPolicy.Evaluation replay = entry.evaluate(new PolicyReplay.Snapshot(GuardSettings.defaults(), Collections.emptyList()), wall.get());
            assertFalse(replay.geo.getResult().isPresent()); assertEquals(FailureReason.NONE, replay.geo.getReason());
            assertFalse(replay.geoFlag); assertEquals(ProviderVote.Status.POSITIVE, replay.vpn.getStatus());
        }
    }
    @Test void failedSaveKeepsThePreviousFileAndAllowsExplicitRetryWithoutMoreSamples() throws Exception {
        capture.start(1000); record(vpn(ProviderVote.Status.NEGATIVE), true);
        Files.createDirectory(directory.resolve("policy")); Path file = directory.resolve("policy/observations.json");
        byte[] other = "external edit".getBytes(StandardCharsets.UTF_8); Files.write(file, other);
        assertThrows(IOException.class, capture::stop); assertArrayEquals(other, Files.readAllBytes(file));
        record(vpn(ProviderVote.Status.POSITIVE), true); Files.delete(file); capture.stop(); assertEquals(1, read().cases.size());
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
    @Test void symlinksCannotBeFollowedAndOptionalCorruptionDoesNotAffectAdmissionCapture() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside")); Files.createSymbolicLink(directory.resolve("policy"), outside);
        assertThrows(IOException.class, () -> capture.start(1000)); assertThrows(IOException.class, capture::clear);
        Files.delete(directory.resolve("policy")); Files.createDirectory(directory.resolve("policy"));
        Path original = directory.resolve("original.json"); Files.write(original, "private".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(directory.resolve("policy/observations.json"), original);
        assertThrows(IOException.class, capture::clear); assertEquals("private", new String(Files.readAllBytes(original), StandardCharsets.UTF_8));
        Files.delete(directory.resolve("policy/observations.json")); Files.write(directory.resolve("policy/observations.json"), "{invalid}".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> capture.configure(directory)); assertThrows(Exception.class, capture::status);
        capture.clear(); capture.start(1000); record(vpn(ProviderVote.Status.NEGATIVE), true); capture.discard();
        assertFalse(Files.exists(directory.resolve("policy/observations.json")));
    }
    @Test void independentConcurrentSavesNeverReplaceTheWinningArchive() throws Exception {
        PolicyObservationCapture other = new PolicyObservationCapture(monotonic::get, wall::get);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            other.configure(directory); capture.start(1000); other.start(1000);
            record(vpn(ProviderVote.Status.POSITIVE), true);
            other.record(Collections.emptyList(), IP, null, false, false, false, vpn(ProviderVote.Status.NEGATIVE),
                    new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 0), null, wall.get(), true);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Boolean>> saves = new ArrayList<>();
            for (PolicyObservationCapture recorder : Arrays.asList(capture, other)) saves.add(workers.submit(() -> {
                release.await();
                try { recorder.stop(); return true; }
                catch (IOException rejected) { return false; }
            }));
            release.countDown();
            boolean first = saves.get(0).get(5, TimeUnit.SECONDS), second = saves.get(1).get(5, TimeUnit.SECONDS);
            assertNotEquals(first, second, "Exactly one exclusive save may commit.");
            ProviderVote.Status expected = first ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE;
            assertEquals(expected, read().cases.get(0).evaluate(new PolicyReplay.Snapshot(GuardSettings.defaults(), Collections.emptyList()), wall.get()).vpn.getStatus());
        } finally { other.close(); workers.shutdownNow(); }
    }
    @Test void sevenDayCleanupWorksAtStatusAndRestartAndShutdownNeverSavesPendingData() throws Exception {
        capture.start(1000, true); record(vpn(ProviderVote.Status.POSITIVE), true); capture.stop();
        wall.addAndGet(PolicyObservationCapture.RETENTION_MILLIS); assertFalse(capture.status().saved);
        assertFalse(Files.exists(directory.resolve("policy/observations.json")));
        capture.start(1000); record(vpn(ProviderVote.Status.NEGATIVE), true); capture.close();
        assertFalse(Files.exists(directory.resolve("policy/observations.json")));
        capture.configure(directory); capture.start(1000); record(vpn(ProviderVote.Status.NEGATIVE), true); capture.stop(); capture.close();
        wall.addAndGet(PolicyObservationCapture.RETENTION_MILLIS); capture.configure(directory);
        assertFalse(Files.exists(directory.resolve("policy/observations.json")));
    }
    @Test void parallelCapturesRemainBoundedAndNeverWaitForFileWrites() throws Exception {
        capture.start(1000); ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 1000; i++) tasks.add(pool.submit(() -> record(vpn(ProviderVote.Status.NEGATIVE), true)));
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            assertTrue(capture.status().samples <= 32); assertTrue(capture.status().samples > 0);
            capture.stop(); assertTrue(read().cases.size() <= 32);
        } finally { pool.shutdownNow(); }
    }
    @Test void interruptedStagingAndLeftoverPublicationLinksCannotOutliveTheArchive() throws Exception {
        Path policy = Files.createDirectory(directory.resolve("policy"));
        Path interrupted = policy.resolve(".cg-observations-123.tmp");
        Files.write(interrupted, "Private interrupted sample".getBytes(StandardCharsets.UTF_8));
        capture.configure(directory);
        assertFalse(Files.exists(interrupted)); assertFalse(capture.status().saved);
        capture.start(1000); record(vpn(ProviderVote.Status.POSITIVE), true); capture.stop();
        Path archive = policy.resolve("observations.json"), leftover = policy.resolve(".cg-observations-456.tmp");
        Files.createLink(leftover, archive);
        wall.addAndGet(PolicyObservationCapture.RETENTION_MILLIS); capture.status();
        assertFalse(Files.exists(leftover)); assertFalse(Files.exists(archive));
    }
}
