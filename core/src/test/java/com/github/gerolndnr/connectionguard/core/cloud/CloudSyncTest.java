package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end against an in-process fake of the cloud API, using the shared protocol fixtures. */
@Timeout(20)
class CloudSyncTest {
    @TempDir Path dir;
    private HttpServer server;
    private final BlockingQueue<JsonObject> syncs = new LinkedBlockingQueue<>();
    private volatile String syncResponse;
    private volatile int syncStatus = 200;
    private volatile int installs;
    private volatile CountDownLatch installEntered, installRelease;

    @BeforeEach void start() throws IOException {
        DecisionObservers.closeAll();
        ConnectionGuard.applySettings(GuardSettings.defaults());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/installs", ex -> {
            installs++;
            if (installEntered != null) {
                installEntered.countDown();
                try { assertTrue(installRelease.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] out = ("{\"install_id\":\"ins_AAAAAAAAAAAAAAAAAAAAAAAA\",\"secret\":\"cgs_" + repeat('s', 48) + "\","
                    + "\"link_code\":\"7KQM-4P2X\",\"link_url\":\"https://app.connectionguard.net/link/7KQM-4P2X\",\"claimed\":false,\"next_sync_in\":60}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(201, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.createContext("/v1/sync", ex -> {
            assertEquals("Bearer ins_AAAAAAAAAAAAAAAAAAAAAAAA.cgs_" + repeat('s', 48), ex.getRequestHeaders().getFirst("Authorization"));
            assertEquals("gzip", ex.getRequestHeaders().getFirst("Content-Encoding"));
            try (Reader r = new InputStreamReader(new GZIPInputStream(ex.getRequestBody()), StandardCharsets.UTF_8)) {
                syncs.add(new Gson().fromJson(r, JsonObject.class));
            }
            byte[] out = syncResponse.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(syncStatus, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.start();
        syncResponse = unclaimed();
    }

    @AfterEach void stop() {
        CloudSync.stop();
        CloudSync.setReloadHook(null);
        server.stop(0);
        DecisionObservers.closeAll();
    }

    private static String repeat(char c, int n) { char[] a = new char[n]; Arrays.fill(a, c); return new String(a); }
    private static String unclaimed() {
        return "{\"next_sync_in\":60,\"live\":false,\"claimed\":false,\"network_name\":null,\"link_code\":\"7KQM-4P2X\","
                + "\"link_url\":\"https://app.connectionguard.net/link/7KQM-4P2X\",\"accept_events\":false,\"commands\":[]}";
    }
    private static String fixture(String name) throws IOException {
        try (InputStream in = CloudSyncTest.class.getResourceAsStream("/cloud-protocol/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void startSync(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(extra);
        config.put("cloud.endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
        CloudSync.start(dir, config::get, Platform.VELOCITY, "Velocity 3.4.0", "0.5.0-test", Logger.getLogger("test"));
    }

    private static DecisionObservation observation(Outcome outcome, Reason reason, Flag... flags) {
        DetectionMetadata meta = new DetectionMetadata(Collections.emptyMap(), 64500L, "Example Hosting", null, "NL", 88, null);
        Source source = new Source("proxycheck", Scope.VPN, new DetectionObservation(DetectionObservation.Status.POSITIVE,
                DetectionObservation.Reason.NONE, meta, 0, null), 170, true, false);
        Source geo = new Source("geo.IpApiGeoProvider", Scope.GEO, new DetectionObservation(DetectionObservation.Status.NEGATIVE,
                DetectionObservation.Reason.NONE, meta, 0, null), 40, false, true);
        EnumSet<Flag> set = EnumSet.noneOf(Flag.class); set.addAll(Arrays.asList(flags));
        return new DecisionObservation(Platform.VELOCITY, Phase.LOGIN, Mode.ENFORCE, IdentityTrust.AUTHENTICATED,
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "203.0.113.24", outcome, reason, Check.POSITIVE, Check.KNOWN,
                System.currentTimeMillis(), 182, false, set, Arrays.asList(source, geo), Collections.emptyList());
    }

    private static void awaitDelivered(long expected) throws InterruptedException {
        for (int i = 0; i < 200 && DecisionObservers.delivered() < expected; i++) Thread.sleep(10);
    }

    @Test void disabledMeansNoRequestAtAll() {
        Map<String, Object> off = new HashMap<>(); off.put("cloud.enabled", false);
        startSync(off);
        CloudSync.runOnceForTest();
        assertEquals(0, installs);
        assertFalse(CloudSync.isRunning());
        assertEquals(-1, DecisionObservers.captureGeneration(), "no capture work when the cloud is off");
    }

    @Test void unlinkedSendsOnlyAnonymousTotals() throws Exception {
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest(); // install
        assertEquals(1, installs);
        assertTrue(Files.exists(dir.resolve("cloud/credentials.json")));
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.DENY, Reason.VPN_FLAG, Flag.VPN));
        awaitDelivered(before + 1);
        CloudSync.runOnceForTest(); // sync
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS);
        assertNotNull(body);
        assertEquals(0, body.getAsJsonArray("events").size(), "no personal data before linking");
        JsonObject c = body.getAsJsonObject("counters");
        assertEquals(1, c.get("checks").getAsInt());
        assertEquals(1, c.get("denied").getAsInt());
        assertEquals(1, c.get("vpn_positive").getAsInt());
        assertEquals(1, c.getAsJsonObject("countries").get("NL").getAsInt());
        assertFalse(body.has("platform"), "only protocol fields are sent");
        assertTrue(CloudSync.linkUrl().isPresent());
    }

    @Test void linkedSendsEventsMatchingTheProtocolFixture() throws Exception {
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        syncResponse = fixture("sync-response.json").replace("\"commands\": [", "\"commands\": [] ,\"ignored\": [")
                .replaceAll("(?s)\"config\": \\{.*?\\n  \\}", "\"config\": null");
        CloudSync.runOnceForTest(); // learns accept_events=true
        syncs.clear();
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.ALLOW, Reason.FLAG_ALLOWED, Flag.VPN));
        awaitDelivered(before + 1);
        CloudSync.runOnceForTest();
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS);
        JsonObject expected = new Gson().fromJson(fixture("sync-request.json"), JsonObject.class);
        assertEquals(expected.keySet(), body.keySet());
        JsonArray capabilities = new JsonArray(); capabilities.add("rule_expiry");
        expected.getAsJsonObject("status").add("capabilities", capabilities);
        assertEquals(expected.getAsJsonObject("status").keySet(), body.getAsJsonObject("status").keySet());
        assertEquals(capabilities, body.getAsJsonObject("status").get("capabilities"));
        assertEquals(expected.getAsJsonObject("counters").keySet(), body.getAsJsonObject("counters").keySet());
        JsonObject event = body.getAsJsonArray("events").get(0).getAsJsonObject();
        JsonObject expectedEvent = expected.getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals(expectedEvent.keySet(), event.keySet());
        assertEquals(expectedEvent.getAsJsonArray("sources").get(0).getAsJsonObject().keySet(),
                event.getAsJsonArray("sources").get(0).getAsJsonObject().keySet());
        assertEquals("geo-ipapigeoprovider", event.getAsJsonArray("sources").get(1).getAsJsonObject().get("id").getAsString());
        assertTrue(event.get("id").getAsString().matches("[0-9a-f-]{36}"));
    }

    @Test void refusesCommandsOutsideTheClosedSet() throws Exception {
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        syncResponse = fixture("sync-response-unknown-command.json");
        CloudSync.runOnceForTest(); // receives console.execute
        syncResponse = unclaimed();
        syncs.clear();
        CloudSync.runOnceForTest(); // reports the result
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS);
        JsonObject result = body.getAsJsonArray("command_results").get(0).getAsJsonObject();
        assertEquals("cmd_zzzzzzzzzzzz", result.get("id").getAsString());
        assertFalse(result.get("ok").getAsBoolean());
    }

    @Test void retriesResendTheSameBodySoNothingIsCountedTwice() throws Exception {
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.ALLOW, Reason.CHECKS_COMPLETE));
        awaitDelivered(before + 1);
        syncStatus = 503; syncResponse = "{\"error\":\"internal\",\"retry_in\":60}";
        CloudSync.runOnceForTest();
        JsonObject failed = syncs.poll(5, TimeUnit.SECONDS);
        syncStatus = 200; syncResponse = unclaimed();
        CloudSync.runOnceForTest();
        JsonObject retried = syncs.poll(5, TimeUnit.SECONDS);
        assertEquals(failed.get("seq"), retried.get("seq"));
        assertEquals(failed.getAsJsonObject("counters"), retried.getAsJsonObject("counters"));
    }

    @Test void commandDisablePersistsAcrossRestarts() throws Exception {
        startSync(Collections.emptyMap());
        assertTrue(CloudSync.isRunning());
        CloudSync.setDisabledByCommand(true);
        assertFalse(CloudSync.isRunning());
        startSync(Collections.emptyMap()); // restart
        assertFalse(CloudSync.isRunning());
        CloudSync.setDisabledByCommand(false);
        assertTrue(CloudSync.isRunning());
    }

    private String linkedWithConfig(String config) {
        return "{\"next_sync_in\":60,\"live\":false,\"claimed\":true,\"network_name\":\"Net\",\"link_code\":null,\"link_url\":null,"
                + "\"accept_events\":true,\"commands\":[],\"config\":" + config + "}";
    }

    @Test void appliesDashboardSettingsThroughTheReloadAndReportsTheResult() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        CloudSync.setReloadHook(reloads::incrementAndGet);
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("{\"version\":2,\"reset\":false,\"keep_secrets\":[],\"values\":{\"operation.mode\":\"ENFORCE\",\"provider.vpn.iphub.api-key\":\"key12345\"}}");
        CloudSync.runOnceForTest();
        assertEquals(1, reloads.get());
        assertEquals("ENFORCE", CloudManagedConfig.load(dir).values.get("operation.mode"));
        syncResponse = linkedWithConfig("null");
        syncs.clear();
        CloudSync.runOnceForTest();
        JsonObject status = syncs.poll(5, TimeUnit.SECONDS).getAsJsonObject("status");
        assertEquals(2, status.get("config_version").getAsInt());
        assertTrue(status.getAsJsonObject("config_result").get("ok").getAsBoolean());
        assertEquals("[\"operation.mode\",\"provider.vpn.iphub.api-key\"]", status.get("managed").toString());
        // Delivered once, then cleared.
        syncs.clear();
        CloudSync.runOnceForTest();
        assertTrue(syncs.poll(5, TimeUnit.SECONDS).getAsJsonObject("status").get("config_result").isJsonNull());
    }

    @Test void rollsBackWhenTheReloadRejectsTheSettings() throws Exception {
        CloudSync.setReloadHook(() -> { throw new IllegalArgumentException("required-positive-flags must be 1..enabled voting provider count (maximum 16)."); });
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("{\"version\":5,\"reset\":false,\"keep_secrets\":[],\"values\":{\"required-positive-flags\":4}}");
        CloudSync.runOnceForTest();
        assertTrue(CloudManagedConfig.load(dir).values.isEmpty(), "previous (empty) overlay restored");
        syncResponse = linkedWithConfig("null");
        syncs.clear();
        CloudSync.runOnceForTest();
        JsonObject result = syncs.poll(5, TimeUnit.SECONDS).getAsJsonObject("status").getAsJsonObject("config_result");
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals(5, result.get("version").getAsInt());
        assertEquals("The server rejected these settings; check /cg doctor and the local configuration (values redacted).", result.get("message").getAsString());
    }

    @Test void retriesLaterWhileLookupsAreInFlight() throws Exception {
        CloudSync.setReloadHook(() -> { throw new IllegalStateException("Wait for lookup workers and deadlines before reloading providers."); });
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("{\"version\":2,\"reset\":false,\"keep_secrets\":[],\"values\":{\"operation.mode\":\"ENFORCE\"}}");
        CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("null");
        syncs.clear();
        CloudSync.runOnceForTest();
        JsonObject status = syncs.poll(5, TimeUnit.SECONDS).getAsJsonObject("status");
        assertTrue(status.get("config_result").isJsonNull(), "no failure reported for a busy server");
        assertTrue(status.get("config_version").isJsonNull());
    }

    @Test void refusesAConfigThatTriesToSetConsoleCommands() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        CloudSync.setReloadHook(reloads::incrementAndGet);
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        syncResponse = fixture("sync-response-forbidden-config.json");
        CloudSync.runOnceForTest();
        assertEquals(0, reloads.get(), "nothing reloaded");
        assertFalse(Files.exists(CloudManagedConfig.file(dir)));
        syncResponse = linkedWithConfig("null");
        syncs.clear();
        CloudSync.runOnceForTest();
        JsonObject result = syncs.poll(5, TimeUnit.SECONDS).getAsJsonObject("status").getAsJsonObject("config_result");
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("Malformed or unsupported settings; values redacted.", result.get("message").getAsString());
    }

    @Test void localResetGoesBackToConfigYml() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        CloudSync.setReloadHook(reloads::incrementAndGet);
        startSync(Collections.emptyMap());
        CloudManagedConfig.write(dir, CloudManagedConfig.next(CloudManagedConfig.EMPTY, 3, false, new Gson().fromJson("{\"operation.mode\":\"ENFORCE\"}", JsonObject.class), null));
        assertTrue(CloudSync.resetSettingsLocally().contains("config.yml applies again"));
        assertTrue(CloudManagedConfig.load(dir).values.isEmpty());
        assertEquals(3, CloudManagedConfig.load(dir).version, "version kept so the dashboard sees the change as current");
        assertEquals(1, reloads.get());
    }
    @Test void internalCloudCapturesActualDecisionWithoutAddonSelection() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        long before = DecisionObservers.delivered();
        try (com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture capture =
                     com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture.begin(Platform.VELOCITY, Phase.LOGIN,
                             "203.0.113.24", null, IdentityTrust.UNTRUSTED)) {
            capture.denied(Reason.VPN_FLAG); capture.flag(Flag.VPN);
        }
        awaitDelivered(before + 1); CloudSync.runOnceForTest();
        JsonObject payload = syncs.poll(5, TimeUnit.SECONDS);
        assertEquals(1, payload.getAsJsonObject("counters").get("denied").getAsInt());
        assertEquals(0, payload.getAsJsonArray("events").size());
        assertFalse(payload.toString().contains("203.0.113.24"));
    }

    @Test void unlinkedReplyCannotRequestPersonalEventsOrActivateSettings() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        CloudSync.setReloadHook(reloads::incrementAndGet);
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("{\"version\":1,\"values\":{\"operation.mode\":\"ENFORCE\"},\"reset\":false,\"keep_secrets\":[]}")
                .replace("\"claimed\":true", "\"claimed\":false");
        CloudSync.runOnceForTest();
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.ALLOW, Reason.CHECKS_COMPLETE)); awaitDelivered(before + 1);
        syncs.clear(); CloudSync.runOnceForTest();
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS);
        assertEquals(0, reloads.get()); assertFalse(Files.exists(CloudManagedConfig.file(dir)));
        assertTrue(body.getAsJsonArray("events").isEmpty()); assertTrue(body.getAsJsonObject("status").get("config").isJsonNull());
        assertFalse(body.toString().contains("203.0.113.24"));
    }

    @Test void disabledInNativeConfigTakesEffectOnRefreshAndKeepsOff() {
        Map<String,Object> config = new HashMap<>();
        config.put("cloud.endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
        CloudSync.start(dir, config::get, Platform.VELOCITY, "test", "0.5.0-test", Logger.getLogger("test"));
        config.put("cloud.enabled", false); CloudSync.refresh();
        assertFalse(CloudSync.isRunning()); CloudSync.runOnceForTest(); assertEquals(0, installs);
        config.put("cloud.enabled", true); CloudSync.refresh(); assertTrue(CloudSync.isRunning());
    }

    @Test void stopCancelsBlockedHttpAndCannotLeaveLateCredentialsOrObserver() throws Exception {
        startSync(Collections.emptyMap());
        installEntered = new CountDownLatch(1); installRelease = new CountDownLatch(1);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = thread.submit(CloudSync::runOnceForTest);
            assertTrue(installEntered.await(5, TimeUnit.SECONDS));
            CloudSync.stop(); task.get(2, TimeUnit.SECONDS);
            installRelease.countDown();
            assertFalse(CloudSync.isRunning()); assertFalse(Files.exists(dir.resolve("cloud/credentials.json")));
            assertEquals(-1, DecisionObservers.captureGeneration());
        } finally { installRelease.countDown(); thread.shutdownNow(); assertTrue(thread.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void rejectionResultNeverCopiesReloadExceptionOrRemotePath() throws Exception {
        String sensitive = "private-api-key-4242";
        CloudSync.setReloadHook(() -> { throw new IllegalArgumentException(sensitive); });
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("{\"version\":2,\"values\":{\"operation.mode\":\"ENFORCE\"},\"reset\":false,\"keep_secrets\":[]}");
        CloudSync.runOnceForTest(); syncs.clear(); syncResponse = linkedWithConfig("null"); CloudSync.runOnceForTest();
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS);
        assertFalse(body.toString().contains(sensitive));
        assertFalse(body.getAsJsonObject("status").getAsJsonObject("config_result").get("ok").getAsBoolean());
        assertTrue(CloudManagedConfig.load(dir).values.isEmpty());
    }

    @Test void unchangedRefreshKeepsRecorderAndPersistedMonotonicSequence() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        CloudRecorder recorder = CloudSync.recorderForTest(); CloudSync.refresh();
        assertSame(recorder, CloudSync.recorderForTest());
        CloudSync.runOnceForTest(); JsonObject first = syncs.poll(5, TimeUnit.SECONDS);
        CloudSync.runOnceForTest(); JsonObject second = syncs.poll(5, TimeUnit.SECONDS);
        assertTrue(second.get("seq").getAsLong() > first.get("seq").getAsLong());
        assertEquals(second.get("seq").getAsLong(), CloudCredentials.load(dir.resolve("cloud/credentials.json")).sequence);
    }

    @Test void slowDraftPreparationDoesNotHoldAdmissionAndRetiredDraftCannotActivate() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger activated = new java.util.concurrent.atomic.AtomicInteger();
        CloudSync.setReloadHook(() -> {
            preparing.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            synchronized (ConnectionGuard.class) { CloudSync.validateReloadActivation(); activated.incrementAndGet(); }
        });
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("{\"version\":2,\"values\":{\"operation.mode\":\"ENFORCE\"},\"reset\":false,\"keep_secrets\":[]}");
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> remote = threads.submit(CloudSync::runOnceForTest);
            assertTrue(preparing.await(3, TimeUnit.SECONDS));
            Future<?> admission = threads.submit(() -> {
                try (com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture capture =
                             com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture.begin(Platform.VELOCITY, Phase.LOGIN,
                                     "203.0.113.24", null, IdentityTrust.UNTRUSTED)) { assertFalse(capture.observe()); }
                CloudSync.stop();
            });
            admission.get(1, TimeUnit.SECONDS);
            release.countDown(); remote.get(2, TimeUnit.SECONDS);
            assertEquals(0, activated.get()); assertTrue(CloudManagedConfig.load(dir).values.isEmpty());
            assertFalse(CloudSync.isRunning());
        } finally { release.countDown(); threads.shutdownNow(); assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void coverageFallbackRetainsCapabilitiesAndTheSameSequenceEventsCountersAndQueuedNewEvents() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("null"); CloudSync.runOnceForTest(); syncs.clear();
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.DENY, Reason.VPN_FLAG, Flag.VPN));
        awaitDelivered(before + 1);
        syncStatus = 400; CloudSync.runOnceForTest();
        JsonObject first = syncs.poll(5, TimeUnit.SECONDS);
        assertNotNull(first); assertTrue(first.getAsJsonObject("status").has("capabilities"));
        assertEquals(1, first.getAsJsonArray("events").size());
        before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.ALLOW, Reason.FLAG_ALLOWED, Flag.VPN)); awaitDelivered(before + 1);
        syncStatus = 200; CloudSync.runOnceForTest();
        JsonObject retried = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(retried);
        JsonObject expected = first.deepCopy(); expected.getAsJsonObject("status").remove("vpn_unchecked_allowed");
        assertEquals(expected, retried, "Only unsupported metadata changes: the exact batch and seq are retained");
        CloudSync.runOnceForTest();
        JsonObject next = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(next);
        assertTrue(next.getAsJsonObject("status").has("capabilities"));
        assertFalse(next.getAsJsonObject("status").has("vpn_unchecked_allowed"));
        assertTrue(next.get("seq").getAsLong() > first.get("seq").getAsLong());
        assertEquals(1, next.getAsJsonArray("events").size());
        assertEquals(1, next.getAsJsonObject("counters").get("allowed").getAsInt());
    }

    @Test void olderApiRejectingBothStatusExtensionsRetriesExactlyTheSameBatchTwice() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.ALLOW, Reason.FLAG_ALLOWED, Flag.VPN)); awaitDelivered(before + 1);
        syncStatus = 400; CloudSync.runOnceForTest(); JsonObject first = syncs.poll(5, TimeUnit.SECONDS);
        assertNotNull(first); assertTrue(first.getAsJsonObject("status").has("vpn_unchecked_allowed"));
        CloudSync.runOnceForTest(); JsonObject second = syncs.poll(5, TimeUnit.SECONDS);
        assertNotNull(second); JsonObject expected = first.deepCopy(); expected.getAsJsonObject("status").remove("vpn_unchecked_allowed");
        assertEquals(expected, second); assertTrue(second.getAsJsonObject("status").has("capabilities"));
        syncStatus = 200; CloudSync.runOnceForTest(); JsonObject third = syncs.poll(5, TimeUnit.SECONDS);
        expected.getAsJsonObject("status").remove("capabilities"); assertEquals(expected, third);
        CloudSync.runOnceForTest(); JsonObject next = syncs.poll(5, TimeUnit.SECONDS);
        assertNotNull(next); assertEquals(0, next.getAsJsonObject("counters").get("allowed").getAsLong());
        assertFalse(next.getAsJsonObject("status").has("vpn_unchecked_allowed"));
        assertFalse(next.getAsJsonObject("status").has("capabilities"));
    }

    @Test void cloudStatusIncludesExactAnonymousVpnCoverageIndependentOfObserverDelivery() throws Exception {
        long before = ConnectionGuard.uncheckedVpnAdmissions().snapshot().total;
        for (int i = 0; i < 52; i++) ConnectionGuard.uncheckedVpnAdmissions().allowed(com.github.gerolndnr.connectionguard.core.lookup.FailureReason.BUDGET_EXHAUSTED);
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest(); CloudSync.runOnceForTest();
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(body);
        JsonObject coverage = body.getAsJsonObject("status").getAsJsonObject("vpn_unchecked_allowed");
        assertNotNull(coverage); assertEquals(before + 52, coverage.get("total").getAsLong());
        assertTrue(coverage.getAsJsonObject("reasons").get("BUDGET_EXHAUSTED").getAsLong() >= 52);
        assertEquals(new HashSet<>(Arrays.asList("total", "since_summary", "window_seconds", "reasons")), coverage.keySet());
        assertEquals(0, body.getAsJsonArray("events").size(), "Unlinked coverage status contains only anonymous counters");
    }

    @Test void malformedTypedCommandGetsRedactedFailureAndDoesNotPreventFollowingCommand() throws Exception {
        ConnectionGuard.initializeRules(dir.resolve("rules"));
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("null").replace("\"commands\":[]", "\"commands\":[{\"id\":\"cmd_AAAAAAAAAAAAAAAA\",\"type\":true},{\"id\":\"cmd_BBBBBBBBBBBBBBBB\",\"type\":\"access_rule.remove\",\"effect\":\"ALLOW\",\"target\":\"192.0.2.12\"}]");
        CloudSync.runOnceForTest(); syncs.clear(); syncResponse = linkedWithConfig("null"); CloudSync.runOnceForTest();
        JsonObject body = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(body);
        JsonArray results = body.getAsJsonArray("command_results"); assertEquals(2, results.size());
        assertFalse(results.get(0).getAsJsonObject().get("ok").getAsBoolean());
        assertEquals("Command refused (values redacted).", results.get(0).getAsJsonObject().get("message").getAsString());
        assertEquals("cmd_BBBBBBBBBBBBBBBB", results.get(1).getAsJsonObject().get("id").getAsString());
        assertTrue(results.get(1).getAsJsonObject().get("ok").getAsBoolean());
    }

}
