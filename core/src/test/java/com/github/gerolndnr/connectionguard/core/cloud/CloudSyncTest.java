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
        CloudSync.setNoticeConsole(null);
        CloudSync.resetErrorReportsForTest();
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
        CloudSync.setNoticeConsole(null);
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
    private static void awaitErrorBuffered() throws InterruptedException {
        for (int i = 0; i < 500 && PluginErrorReports.bufferedForTest() == 0; i++) Thread.sleep(10);
        assertTrue(PluginErrorReports.bufferedForTest() > 0, "The background collector has finished before testing the next sync.");
    }

    @Test void disabledMeansNoRequestAtAll() {
        Map<String, Object> off = new HashMap<>(); off.put("cloud.enabled", false);
        startSync(off);
        CloudSync.runOnceForTest();
        assertEquals(0, installs);
        assertFalse(CloudSync.isRunning());
        assertEquals(-1, DecisionObservers.captureGeneration(), "no capture work when the cloud is off");
    }

    @Test void freshInstallAnnouncesSetupAndOnlyNewLinkCodesProduceAnotherBanner() {
        List<CloudLinkNotice> notices = new CopyOnWriteArrayList<>();
        CloudSync.setNoticeConsole(notices::add);
        startSync(Collections.emptyMap());
        assertEquals(1, notices.size());
        assertEquals("https://app.connectionguard.net?src=console", notices.get(0).url);
        assertTrue(CloudSync.noticeCurrent(notices.get(0)));
        CloudSync.runOnceForTest();
        assertEquals(2, notices.size());
        assertFalse(CloudSync.noticeCurrent(notices.get(0)), "stale queued presentation is suppressed");
        assertEquals("https://app.connectionguard.net/link/7KQM-4P2X?src=console", notices.get(1).url);
        assertEquals(8, notices.get(1).consoleLines().size());
        assertEquals(notices.get(1).consoleLines().get(0), notices.get(1).consoleLines().get(7));
        CloudSync.runOnceForTest();
        assertEquals(2, notices.size());
        syncResponse = unclaimed().replace("7KQM-4P2X", "9ABC-2DEF");
        CloudSync.runOnceForTest();
        assertEquals(3, notices.size());
        assertTrue(notices.get(2).url.contains("9ABC-2DEF?src=console"));
        syncResponse = linkedWithConfig("null");
        CloudSync.runOnceForTest();
        assertEquals(3, notices.size());
        assertFalse(CloudSync.noticeCurrent(notices.get(2)));
        assertFalse(CloudSync.takeJoinNotice(UUID.randomUUID(), true, false).isPresent());
    }

    @Test void joinHintIsOncePerStaffAcrossReloadsAndCleanRestartsAndIsLocalOnly() throws Exception {
        UUID staff = UUID.randomUUID(), other = UUID.randomUUID();
        startSync(Collections.emptyMap());
        CloudSync.runOnceForTest();
        assertFalse(CloudSync.takeJoinNotice(staff, false, false).isPresent());
        assertFalse(CloudSync.takeJoinNotice(staff, true, true).isPresent());
        CloudLinkNotice notice = CloudSync.takeJoinNotice(staff, true, false).get();
        assertEquals("https://app.connectionguard.net/link/7KQM-4P2X?src=join", notice.url);
        assertFalse(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        startSync(Collections.emptyMap()); // reload reuses the running link
        assertFalse(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        CloudSync.stop(); // guarantees a clean local flush even if a background save is still queued
        String stored = new String(Files.readAllBytes(dir.resolve("cloud/staff-dashboard-notices-v1.json")), StandardCharsets.UTF_8);
        assertFalse(stored.contains(staff.toString()));
        assertFalse(stored.contains("7KQM-4P2X"));
        startSync(Collections.emptyMap());
        assertTrue(CloudSync.awaitingLinkState());
        assertFalse(CloudSync.takeJoinNotice(other, true, false).isPresent());
        CloudSync.runOnceForTest();
        assertFalse(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        assertTrue(CloudSync.takeJoinNotice(other, true, false).isPresent());
        syncs.clear(); CloudSync.runOnceForTest();
        String payload = syncs.poll(5, TimeUnit.SECONDS).toString();
        assertFalse(payload.contains(staff.toString()));
        assertFalse(payload.contains(other.toString()));
        assertFalse(payload.contains("staff-dashboard"));
        assertFalse(payload.contains("src=join"));
    }

    @Test void returningLinkedInstallWaitsForActualStateAndStaysQuiet() {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest(); CloudSync.stop();
        List<CloudLinkNotice> notices = new CopyOnWriteArrayList<>();
        CloudSync.setNoticeConsole(notices::add);
        syncResponse = linkedWithConfig("null");
        startSync(Collections.emptyMap());
        assertTrue(CloudSync.awaitingLinkState());
        assertTrue(notices.isEmpty());
        assertFalse(CloudSync.takeJoinNotice(UUID.randomUUID(), true, false).isPresent());
        CloudSync.runOnceForTest();
        assertTrue(CloudSync.isLinked());
        assertFalse(CloudSync.awaitingLinkState());
        assertTrue(notices.isEmpty());
        assertFalse(CloudSync.takeJoinNotice(UUID.randomUUID(), true, false).isPresent());
    }

    @Test void returningUnlinkedInstallAnnouncesConfirmedLinkOnceOnRestart() {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest(); CloudSync.stop();
        List<CloudLinkNotice> notices = new CopyOnWriteArrayList<>();
        CloudSync.setNoticeConsole(notices::add);
        startSync(Collections.emptyMap());
        assertTrue(notices.isEmpty());
        CloudSync.runOnceForTest(); CloudSync.runOnceForTest();
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).url.endsWith("?src=console"));
    }

    @Test void disabledCloudDoesNotAnnounceOrConsumeStaffHint() throws Exception {
        List<CloudLinkNotice> notices = new CopyOnWriteArrayList<>();
        CloudSync.setNoticeConsole(notices::add);
        UUID staff = UUID.randomUUID();
        startSync(Collections.singletonMap("cloud.enabled", false));
        assertFalse(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        assertTrue(notices.isEmpty());
        startSync(Collections.emptyMap());
        assertTrue(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        CloudLinkNotice pending = notices.get(0);
        CloudSync.setDisabledByCommand(true);
        assertFalse(CloudSync.noticeCurrent(pending));
        assertFalse(CloudSync.takeJoinNotice(UUID.randomUUID(), true, false).isPresent());
    }

    @Test void invalidNoticePreferencesCannotDisableCloud() throws Exception {
        Files.createDirectories(dir.resolve("cloud"));
        Files.write(dir.resolve("cloud/staff-dashboard-notices-v1.json"), "invalid".getBytes(StandardCharsets.UTF_8));
        startSync(Collections.emptyMap());
        assertTrue(CloudSync.isRunning());
        UUID staff = UUID.randomUUID();
        assertTrue(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        assertFalse(CloudSync.takeJoinNotice(staff, true, false).isPresent());
        CloudSync.runOnceForTest();
        assertEquals(1, installs);
    }

    @Test void existingInstallGetsOneErrorReportingNoticeAcrossReloadsAndRestarts() throws Exception {
        Files.createDirectories(dir.resolve("cloud")); Files.write(dir.resolve("cloud/notice-v1"), new byte[0]);
        java.util.concurrent.atomic.AtomicInteger notices = new java.util.concurrent.atomic.AtomicInteger();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().startsWith("Connection Guard error reports are on:")) notices.incrementAndGet();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger("test"); logger.addHandler(handler);
        try {
            startSync(Collections.emptyMap()); assertEquals(1, notices.get());
            startSync(Collections.emptyMap()); startSync(Collections.singletonMap("cloud.error-reports", false));
            startSync(Collections.emptyMap()); CloudSync.stop(); startSync(Collections.emptyMap());
            assertEquals(1, notices.get()); assertTrue(Files.exists(dir.resolve("cloud/error-reports-notice-v1")));
        } finally { logger.removeHandler(handler); }
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
        JsonArray capabilities = new JsonArray(); capabilities.add("rule_expiry"); capabilities.add("sync_command");
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

    @Test void oldStrictApiFallbackRetainsTheSameSequenceEventsCountersAndQueuedNewEvents() throws Exception {
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
        JsonObject expected = first.deepCopy(); expected.getAsJsonObject("status").remove("capabilities");
        assertEquals(expected, retried, "Only unsupported metadata changes: the exact batch and seq are retained");
        CloudSync.runOnceForTest();
        JsonObject next = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(next);
        assertFalse(next.getAsJsonObject("status").has("capabilities"));
        assertTrue(next.get("seq").getAsLong() > first.get("seq").getAsLong());
        assertEquals(1, next.getAsJsonArray("events").size());
        assertEquals(1, next.getAsJsonObject("counters").get("allowed").getAsInt());
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

    @Test void anonymousExceptionBatchUsesExactOptionalCloudContractAndClearsAfterSync() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        PluginErrorReports.record(PluginErrorReportsTest.failure(7), PluginErrorReports.Context.LOOKUP);
        awaitErrorBuffered();
        CloudSync.runOnceForTest(); JsonObject first = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(first);
        JsonObject expected = JsonParser.parseString(fixture("sync-request-errors.json")).getAsJsonObject();
        assertEquals(expected.keySet(), first.keySet());
        JsonObject report = first.getAsJsonArray("errors").get(0).getAsJsonObject();
        assertEquals(expected.getAsJsonArray("errors").get(0).getAsJsonObject().keySet(), report.keySet());
        assertEquals(expected.getAsJsonArray("errors").get(0).getAsJsonObject().getAsJsonArray("frames").get(0).getAsJsonObject().keySet(),
                report.getAsJsonArray("frames").get(0).getAsJsonObject().keySet());
        assertEquals(0, first.getAsJsonArray("events").size());
        for (String canary : PluginErrorReportsTest.CANARY.split(" ")) assertFalse(first.toString().contains(canary));
        CloudSync.runOnceForTest(); assertFalse(syncs.poll(5, TimeUnit.SECONDS).has("errors"));
        assertEquals(1, installs, "Reporting reuses normal syncs and never adds an install or separate request.");
    }

    @Test void errorReportOptOutOmitsFieldAndIsVisibleInDoctor() throws Exception {
        startSync(Collections.singletonMap("cloud.error-reports", false)); CloudSync.runOnceForTest();
        PluginErrorReports.record(PluginErrorReportsTest.failure(7), PluginErrorReports.Context.STARTUP);
        CloudSync.runOnceForTest(); assertFalse(syncs.poll(5, TimeUnit.SECONDS).has("errors"));
        assertTrue(CloudSync.describeLines().stream().anyMatch(line -> line.startsWith("Cloud error reports: off")));
        assertTrue(com.github.gerolndnr.connectionguard.core.commands.OperationsCommands.doctor().stream()
                .anyMatch(line -> line.startsWith("Cloud error reports: off")));
    }

    @Test void olderCloudRetriesIdenticalBatchOnceWithoutErrorsUntilRestart() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        syncResponse = linkedWithConfig("null"); CloudSync.runOnceForTest(); syncs.clear();
        long before = DecisionObservers.delivered();
        DecisionObservers.publish(observation(Outcome.ALLOW, Reason.FLAG_ALLOWED, Flag.VPN)); awaitDelivered(before + 1);
        PluginErrorReports.record(PluginErrorReportsTest.failure(7), PluginErrorReports.Context.SYNC);
        awaitErrorBuffered();
        syncStatus = 400; CloudSync.runOnceForTest(); JsonObject first = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(first);
        assertEquals(1, first.getAsJsonArray("events").size(), "The error fallback also retains an actual decision batch.");
        assertTrue(first.has("errors")); assertTrue(first.getAsJsonObject("status").has("capabilities"));
        syncStatus = 200; CloudSync.runOnceForTest(); JsonObject retry = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(retry);
        JsonObject without = first.deepCopy(); without.remove("errors"); assertEquals(without, retry);
        assertTrue(CloudSync.describeLines().stream().anyMatch(line -> line.contains("disabled until restart")));
        CloudSync.stop(); startSync(Collections.emptyMap());
        PluginErrorReports.record(PluginErrorReportsTest.failure(8), PluginErrorReports.Context.OTHER);
        CloudSync.runOnceForTest(); assertFalse(syncs.poll(5, TimeUnit.SECONDS).has("errors"));
    }

    @Test void errorsFallbackAndCapabilityFallbackPreserveSameSequenceAndBatch() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        PluginErrorReports.record(PluginErrorReportsTest.failure(7), PluginErrorReports.Context.SYNC);
        awaitErrorBuffered();
        syncStatus = 400; CloudSync.runOnceForTest(); JsonObject first = syncs.poll(5, TimeUnit.SECONDS);
        CloudSync.runOnceForTest(); JsonObject second = syncs.poll(5, TimeUnit.SECONDS);
        assertNotNull(first); assertNotNull(second); JsonObject expected = first.deepCopy(); expected.remove("errors"); assertEquals(expected, second);
        syncStatus = 200; CloudSync.runOnceForTest(); JsonObject third = syncs.poll(5, TimeUnit.SECONDS);
        expected.getAsJsonObject("status").remove("capabilities"); assertEquals(expected, third);
    }

    @Test void disablingReportsAlsoRemovesErrorsFromAnUnsentRetryBatch() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        PluginErrorReports.record(PluginErrorReportsTest.failure(7), PluginErrorReports.Context.CACHE);
        awaitErrorBuffered();
        syncStatus = 503; CloudSync.runOnceForTest(); JsonObject first = syncs.poll(5, TimeUnit.SECONDS); assertNotNull(first); assertTrue(first.has("errors"));
        startSync(Collections.singletonMap("cloud.error-reports", false));
        syncStatus = 200; CloudSync.runOnceForTest(); JsonObject next = syncs.poll(5, TimeUnit.SECONDS);
        JsonObject expected = first.deepCopy(); expected.remove("errors"); assertEquals(expected, next);
    }

    @Test void cloudCommandOptOutPurgesQueuedErrors() throws Exception {
        startSync(Collections.emptyMap()); CloudSync.runOnceForTest();
        PluginErrorReports.record(PluginErrorReportsTest.failure(7), PluginErrorReports.Context.CACHE);
        CloudSync.setDisabledByCommand(true);
        assertTrue(CloudSync.describeLines().stream().anyMatch(line -> line.startsWith("Cloud error reports: off")));
        assertEquals(0, PluginErrorReports.drainForSync().size()); CloudSync.runOnceForTest(); assertEquals(0, syncs.size());
        CloudSync.setDisabledByCommand(false); CloudSync.runOnceForTest(); assertFalse(syncs.poll(5, TimeUnit.SECONDS).has("errors"));
    }

}
