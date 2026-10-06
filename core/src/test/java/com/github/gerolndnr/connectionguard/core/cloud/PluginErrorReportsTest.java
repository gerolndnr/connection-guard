package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginErrorReportsTest {
    static final String OWN = "com.github.gerolndnr.connectionguard.core.ConnectionGuard";
    static final String CANARY = "203.0.113.91 069a79f4-44e9-4726-a5be-fca90e38aaf5 cgn_CANARY_SECRET /private/server/config.yml https://example.org/?key=CANARY";
    static Throwable failure(int line) {
        Throwable failure = new IllegalStateException(CANARY);
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("other.plugin.Other", "other", CANARY, 50),
                new StackTraceElement(OWN, "lookupVpn", CANARY, line)});
        return failure;
    }
    @Test void onlyOwnFramesAndNoMessagesFilesOrSuppressedData() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1000, false)) {
            reports.setEnabled(true);
            Throwable error = failure(31); error.initCause(new java.net.SocketTimeoutException(CANARY));
            error.getCause().setStackTrace(new StackTraceElement[]{new StackTraceElement("foreign.Other", "name", CANARY, 1)});
            error.addSuppressed(new IllegalArgumentException(CANARY));
            reports.offer(error, PluginErrorReports.Context.LOOKUP);
            JsonObject body = reports.drain().get(0).getAsJsonObject();
            assertEquals(new HashSet<>(Arrays.asList("type", "cause_type", "frames", "context", "fingerprint", "count", "first_at", "last_at")), body.keySet());
            assertEquals("java.net.SocketTimeoutException", body.get("cause_type").getAsString());
            assertEquals(1, body.getAsJsonArray("frames").size());
            JsonObject frame = body.getAsJsonArray("frames").get(0).getAsJsonObject();
            assertEquals(new HashSet<>(Arrays.asList("class", "method", "line")), frame.keySet());
            assertEquals(OWN, frame.get("class").getAsString());
            for (String canary : CANARY.split(" ")) assertFalse(body.toString().contains(canary));
            assertFalse(body.toString().contains("other.plugin")); assertFalse(body.toString().contains("suppressed"));
        }
    }
    @Test void foreignOnlyAndLookalikePackageAreIgnored() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1000, false)) {
            reports.setEnabled(true);
            for (String name : Arrays.asList("org.bukkit.Server", "another.plugin.Failure", "com.github.gerolndnr.connectionguardevil.CG")) {
                Throwable error = failure(1); error.setStackTrace(new StackTraceElement[]{new StackTraceElement(name, "execute", CANARY, 1)});
                reports.offer(error, PluginErrorReports.Context.OTHER);
            }
            assertEquals(0, reports.drain().size());
        }
    }
    @Test void messagesAreNeverEvenRead() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true);
            Throwable error = new IllegalStateException() { @Override public String getMessage() { fail("Exception messages must never be read."); return ""; } };
            error.setStackTrace(failure(1).getStackTrace()); reports.offer(error, PluginErrorReports.Context.CACHE);
            assertEquals(1, reports.drain().size());
        }
    }
    @Test void mergesRepeatedFingerprintsWithTrueTimeRange() {
        AtomicLong clock = new AtomicLong(1000);
        try (PluginErrorReports reports = new PluginErrorReports(clock::getAndIncrement, false)) {
            reports.setEnabled(true);
            for (int i = 0; i < 1000; i++) { reports.offer(failure(7), PluginErrorReports.Context.RELOAD); if (i % 25 == 24) reports.processPending(); }
            JsonObject error = reports.drain().get(0).getAsJsonObject();
            assertEquals(1000, error.get("count").getAsInt()); assertEquals(1000, error.get("first_at").getAsLong());
            assertEquals(1999, error.get("last_at").getAsLong()); assertEquals(0, reports.drain().size());
            assertEquals(0, reports.dropped());
        }
    }
    @Test void capsBothIngressAndFingerprintsThenSendsTenAtATime() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true);
            for (int i = 0; i < 51; i++) reports.offer(failure(i), PluginErrorReports.Context.OTHER);
            reports.processPending(); assertEquals(50, reports.buffered()); assertEquals(1, reports.dropped());
            reports.offer(failure(999), PluginErrorReports.Context.OTHER); reports.processPending();
            assertEquals(50, reports.buffered()); assertEquals(2, reports.dropped());
            for (int i = 0; i < 5; i++) assertEquals(10, reports.drain().size());
            assertEquals(0, reports.drain().size());
        }
    }
    @Test void rawAndSanitizedEntriesShareTheSameFiftySlotLimit() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true);
            for (int i = 0; i < 40; i++) reports.offer(failure(i), PluginErrorReports.Context.OTHER);
            reports.processPending();
            for (int i = 40; i < 100; i++) reports.offer(failure(i), PluginErrorReports.Context.OTHER);
            assertEquals(50, reports.dropped());
            reports.processPending(); assertEquals(50, reports.buffered());
            reports.drain(); reports.offer(failure(101), PluginErrorReports.Context.OTHER);
            reports.processPending(); assertEquals(41, reports.buffered());
            assertEquals(50, reports.dropped(), "Draining returns capacity to the shared bound.");
        }
    }
    @Test void concurrentProducersMergeWithoutLostCountsOrCapacity() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        ExecutorService producers = Executors.newFixedThreadPool(8);
        try (PluginErrorReports reports = new PluginErrorReports(clock::getAndIncrement, false)) {
            reports.setEnabled(true);
            for (int round = 0; round < 20; round++) {
                List<Future<?>> jobs = new ArrayList<>();
                for (int producer = 0; producer < 8; producer++) jobs.add(producers.submit(() -> {
                    for (int i = 0; i < 4; i++) reports.offer(failure(7), PluginErrorReports.Context.LOOKUP);
                }));
                for (Future<?> job : jobs) job.get(2, TimeUnit.SECONDS);
                reports.processPending();
            }
            JsonObject error = reports.drain().get(0).getAsJsonObject();
            assertEquals(640, error.get("count").getAsInt()); assertEquals(1000, error.get("first_at").getAsLong());
            assertEquals(1639, error.get("last_at").getAsLong()); assertEquals(0, reports.dropped());
        } finally { producers.shutdownNow(); }
    }
    @Test void framesAreBoundedAndUnknownLinesAreNull() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true); Throwable error = failure(1);
            StackTraceElement[] frames = new StackTraceElement[30];
            for (int i = 0; i < frames.length; i++) frames[i] = new StackTraceElement(OWN, "<init>", CANARY, -1);
            error.setStackTrace(frames); reports.offer(error, PluginErrorReports.Context.STARTUP);
            JsonArray actual = reports.drain().get(0).getAsJsonObject().getAsJsonArray("frames");
            assertEquals(12, actual.size()); assertTrue(actual.get(0).getAsJsonObject().get("line").isJsonNull());
        }
    }
    @Test void causeFramesCountButCyclesAndUnboundedChainsDoNot() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true);
            Throwable wrapper = new RuntimeException(CANARY, failure(1)); wrapper.setStackTrace(new StackTraceElement[0]);
            reports.offer(wrapper, PluginErrorReports.Context.SYNC);
            assertEquals("java.lang.IllegalStateException", reports.drain().get(0).getAsJsonObject().get("cause_type").getAsString());
            Throwable a = failure(2), b = failure(3); a.initCause(b); b.initCause(a);
            reports.offer(a, PluginErrorReports.Context.SYNC); assertEquals(0, reports.drain().size());
        }
    }
    @Test void optOutPurgesOldOccurrencesAndDoesNotResurrectThem() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true); reports.offer(failure(1), PluginErrorReports.Context.STARTUP); reports.processPending();
            reports.offer(failure(2), PluginErrorReports.Context.STARTUP); reports.setEnabled(false);
            reports.offer(failure(3), PluginErrorReports.Context.STARTUP); reports.setEnabled(true);
            assertEquals(0, reports.drain().size()); reports.offer(failure(4), PluginErrorReports.Context.COMMAND);
            assertEquals(1, reports.drain().size());
        }
    }
    @Test void fingerprintDependsOnTypeAndFirstFiveOwnFramesOnly() {
        try (PluginErrorReports reports = new PluginErrorReports(() -> 1, false)) {
            reports.setEnabled(true);
            Throwable a = failure(7), b = new IllegalStateException("different text"); b.setStackTrace(a.getStackTrace());
            reports.offer(a, PluginErrorReports.Context.CACHE); String first = reports.drain().get(0).getAsJsonObject().get("fingerprint").getAsString();
            reports.offer(b, PluginErrorReports.Context.COMMAND); String second = reports.drain().get(0).getAsJsonObject().get("fingerprint").getAsString();
            assertEquals(first, second); assertTrue(first.matches("[0-9a-f]{16}"));
            assertEquals("aa37c40422e41d92", first);
        }
    }
    @Test void reportValuesMatchTheSharedCloudContractFixture() throws Exception {
        JsonObject expected;
        try (java.io.Reader in = new java.io.InputStreamReader(getClass().getResourceAsStream("/cloud-protocol/sync-request-errors.json"), java.nio.charset.StandardCharsets.UTF_8)) {
            expected = new Gson().fromJson(in, JsonObject.class).getAsJsonArray("errors").get(0).getAsJsonObject();
        }
        StackTraceElement[] frames = new StackTraceElement[expected.getAsJsonArray("frames").size()];
        for (int i = 0; i < frames.length; i++) {
            JsonObject frame = expected.getAsJsonArray("frames").get(i).getAsJsonObject();
            frames[i] = new StackTraceElement(frame.get("class").getAsString(), frame.get("method").getAsString(), CANARY, frame.get("line").getAsInt());
        }
        Throwable error = new IllegalStateException(CANARY, new java.net.SocketTimeoutException(CANARY));
        error.setStackTrace(frames); error.getCause().setStackTrace(new StackTraceElement[0]);
        AtomicLong clock = new AtomicLong();
        try (PluginErrorReports reports = new PluginErrorReports(clock::get, false)) {
            reports.setEnabled(true);
            long first = expected.get("first_at").getAsLong(), last = expected.get("last_at").getAsLong();
            for (int i = 0; i < 37; i++) {
                clock.set(first + (last - first) * i / 36); reports.offer(error, PluginErrorReports.Context.LOOKUP);
            }
            // The fixture's fingerprint is a schema example; this one is computed from its frames.
            expected.addProperty("fingerprint", "ddddea73369120ef");
            assertEquals(expected, reports.drain().get(0).getAsJsonObject());
        }
    }
    @Test void stalledErrorProcessingCannotWaitOnLoginOrLookupWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Throwable slow = new IllegalStateException(CANARY) {
            @Override public StackTraceElement[] getStackTrace() {
                entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return failure(1).getStackTrace();
            }
        };
        try (PluginErrorReports reports = new PluginErrorReports(System::currentTimeMillis, true);
             LookupRuntime lookups = new LookupRuntime(LookupSettings.defaults())) {
            reports.setEnabled(true); reports.offer(slow, PluginErrorReports.Context.LOOKUP);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) reports.offer(failure(1), PluginErrorReports.Context.OTHER);
            for (int i = 0; i < 50; i++) assertEquals(42, lookups.submit(() -> 42).get(500, TimeUnit.MILLISECONDS).intValue());
            assertEquals(1, release.getCount(), "Every normal check finishes while reporting is still deliberately stalled.");
            assertTrue(reports.dropped() >= 950);
        } finally { release.countDown(); }
    }
}
