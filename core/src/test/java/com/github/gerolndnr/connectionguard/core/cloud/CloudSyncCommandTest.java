package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Platform;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.CloudCommands;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Manual commands use the actual background client against owned loopback, including timer races. */
@Timeout(20)
class CloudSyncCommandTest {
    @TempDir Path dir;
    private HttpServer server;
    private ExecutorService handlers;
    private final BlockingQueue<JsonObject> payloads = new LinkedBlockingQueue<>();
    private final AtomicInteger requests = new AtomicInteger(), installs = new AtomicInteger();
    private final Map<String,Object> config = new HashMap<>();
    private volatile String response;
    private volatile int status = 200;
    private volatile CountDownLatch entered, release;

    @BeforeEach void before() throws Exception {
        CloudSync.stop(); CloudSync.resetManualSyncForTest(); CloudSync.resetErrorReportsForTest();
        CloudSync.setNoticeConsole(null); CloudSync.setReloadHook(null); DecisionObservers.closeAll();
        ConnectionGuard.applySettings(GuardSettings.defaults());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        handlers = Executors.newCachedThreadPool(); server.setExecutor(handlers);
        server.createContext("/v1/sync", exchange -> {
            requests.incrementAndGet();
            try (Reader input = new InputStreamReader(new GZIPInputStream(exchange.getRequestBody()),StandardCharsets.UTF_8)) {
                payloads.add(new Gson().fromJson(input,JsonObject.class));
            }
            CountDownLatch hold = release, ready = entered;
            if (hold != null) { ready.countDown(); try { hold.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body); }
            catch (IOException retired) { /* Explicit stop closes the client's in-flight socket. */ }
            finally { exchange.close(); }
        });
        server.createContext("/v1/installs", exchange -> { installs.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close(); });
        server.start();config.put("cloud.endpoint",endpoint());config.put("cloud.error-reports",false);
        response = linked("null",60);
    }
    @AfterEach void after() throws Exception {
        CloudSync.stop(); if (release != null) release.countDown();
        CloudSync.setReloadHook(null);server.stop(0);handlers.shutdownNow();
        assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));DecisionObservers.closeAll();CloudSync.resetManualSyncForTest();
    }
    private String endpoint() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    private static String linked(String desired,int interval) {
        return "{\"next_sync_in\":"+interval+",\"live\":false,\"claimed\":true,\"network_name\":\"Fixture\","
                +"\"link_code\":null,\"link_url\":null,\"accept_events\":true,\"commands\":[],\"config\":"+desired+"}";
    }
    private void start() { CloudSync.start(dir,config::get,Platform.VELOCITY,"synthetic","0.6.0-test",Logger.getLogger("sync-command-test")); }
    private void linkedStart() throws Exception {
        new CloudCredentials("ins_AAAAAAAAAAAAAAAAAAAAAAAA","cgs_"+String.join("",Collections.nCopies(48,"s")),endpoint()).save(dir.resolve("cloud/credentials.json"));
        start();CloudSync.runOnceForTest();assertTrue(CloudSync.isLinked());payloads.clear();requests.set(0);
    }
    private JsonObject received() throws Exception { JsonObject value=payloads.poll(7,TimeUnit.SECONDS);assertNotNull(value);return value; }
    private String finish(CloudSync.SyncRequest request) throws Exception { return request.completion.get(5,TimeUnit.SECONDS); }
    private void awaitScheduled(long minimumMillis) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(CloudSync.scheduledDelayForTest()<minimumMillis&&System.nanoTime()<deadline)Thread.sleep(2);
        assertTrue(CloudSync.scheduledDelayForTest()>=minimumMillis);
    }
    private static String desired(int version) {
        return "{\"version\":"+version+",\"reset\":false,\"keep_secrets\":[],\"values\":{\"operation.mode\":\"ENFORCE\"}}";
    }

    @Test void commandAcknowledgesAndMakesOneRequestThenRegularScheduleContinues() throws Exception {
        linkedStart();response=linked("null",5);BlockingQueue<String> replies=new LinkedBlockingQueue<>();
        assertTrue(CloudCommands.handle(new String[]{"cloud","sync"},p->p.equals("connectionguard.command.cloud"),replies::add));
        assertEquals("Syncing with Connection Guard Cloud…",replies.poll(1,TimeUnit.SECONDS));
        assertEquals("Up to date (settings v0).",replies.poll(5,TimeUnit.SECONDS));
        JsonObject body=received();assertTrue(body.getAsJsonObject("status").getAsJsonArray("capabilities").contains(new JsonPrimitive("sync_command")));
        assertEquals(1,requests.get());assertTrue(CloudSync.scheduledDelayForTest()>3500);
        assertNull(payloads.poll(300,TimeUnit.MILLISECONDS));received();assertEquals(2,requests.get());
        awaitScheduled(3500);assertNull(replies.poll(100,TimeUnit.MILLISECONDS));
    }
    @Test void permissionDeniedNeverRequestsASync() throws Exception {
        linkedStart();List<String> replies=new ArrayList<>();CloudCommands.handle(new String[]{"cloud","sync"},p->false,replies::add);
        assertEquals(Collections.singletonList(ConnectionGuard.getMessages().getString("ops.permission")),replies);assertEquals(0,requests.get());
    }
    @Test void sharedCooldownRejectsDifferentCallersAndSurvivesReload() throws Exception {
        linkedStart();CloudSync.SyncRequest first=CloudSync.requestSync();assertEquals("Up to date (settings v0).",finish(first));received();
        List<String> replies=new ArrayList<>();CloudCommands.handle(new String[]{"cloud","sync"},p->true,replies::add);
        assertTrue(replies.get(0).startsWith("Please wait "));CloudSync.refresh();
        assertTrue(CloudSync.requestSync().initialMessage.startsWith("Please wait "));assertEquals(1,requests.get());
        assertEquals("Up to date (settings v0).",finish(CloudSync.requestSync(System.nanoTime()+TimeUnit.SECONDS.toNanos(11))));received();assertEquals(2,requests.get());
    }
    @Test void offOrNotRunningMakesNoRequest() throws Exception {
        assertTrue(CloudSync.requestSync().initialMessage.contains("Cloud is off"));config.put("cloud.enabled",false);start();
        CloudSync.SyncRequest off=CloudSync.requestSync();assertTrue(off.initialMessage.contains("Cloud is off"));assertNull(finish(off));
        assertEquals(0,requests.get());assertEquals(0,installs.get());
    }
    @Test void unlinkedAndUnknownLinkStateDoNotInstallOrSyncFromTheCommand() throws Exception {
        start();assertEquals(ConnectionGuard.getMessages().getString("cloud.registering-help"),CloudSync.requestSync().initialMessage);
        CloudSync.stop();new CloudCredentials("ins_AAAAAAAAAAAAAAAAAAAAAAAA","cgs_"+String.join("",Collections.nCopies(48,"s")),endpoint()).save(dir.resolve("cloud/credentials.json"));
        start();assertTrue(CloudSync.awaitingLinkState());assertEquals(ConnectionGuard.getMessages().getString("cloud.registering-help"),CloudSync.requestSync().initialMessage);
        assertEquals(0,requests.get());assertEquals(0,installs.get());
    }
    @Test void deliveredConfigUsesBackgroundReloadAndIsReportedAfterMinDelay() throws Exception {
        linkedStart();AtomicInteger reloads=new AtomicInteger();AtomicReference<String> thread=new AtomicReference<>();
        CloudSync.setReloadHook(()->{thread.set(Thread.currentThread().getName());reloads.incrementAndGet();CloudManagedConfig.overlay(dir,config::put);CloudSync.refresh();});
        response=linked(desired(2),60);assertEquals("Applied dashboard settings v2.",finish(CloudSync.requestSync()));
        assertEquals("ConnectionGuard-cloud",thread.get());assertEquals(1,reloads.get());assertEquals(2,CloudManagedConfig.load(dir).version);
        assertEquals("ENFORCE",config.get("operation.mode"));received();assertTrue(CloudSync.scheduledDelayForTest()>3500);
        JsonObject reported=received().getAsJsonObject("status");assertEquals(2,reported.get("config_version").getAsInt());
        assertTrue(reported.getAsJsonObject("config_result").get("ok").getAsBoolean());assertEquals(1,reloads.get());
    }
    @Test void rejectedConfigReportsFailureAndKeepsPreviousValuesWithoutExceptionText() throws Exception {
        linkedStart();response=linked(desired(3),60);CloudSync.setReloadHook(()->{throw new IllegalArgumentException("synthetic-secret-do-not-repeat");});
        String reply=finish(CloudSync.requestSync());assertTrue(reply.startsWith("Cloud sync failed:"));assertFalse(reply.contains("synthetic-secret"));
        assertEquals(0,CloudManagedConfig.load(dir).version);assertFalse(reply.contains("Up to date"));
    }
    @Test void busyConfigIsPendingAndRetriedThroughExistingPath() throws Exception {
        linkedStart();response=linked(desired(4),60);CloudSync.setReloadHook(()->{throw new IllegalStateException("synthetic busy");});
        assertEquals(ConnectionGuard.getMessages().getString("cloud.sync-pending"),finish(CloudSync.requestSync()));received();assertEquals(0,CloudManagedConfig.load(dir).version);
        AtomicInteger reloads=new AtomicInteger();CloudSync.setReloadHook(reloads::incrementAndGet);CloudSync.runOnceForTest();
        assertEquals(4,CloudManagedConfig.load(dir).version);assertEquals(1,reloads.get());
    }
    @Test void syncLimiterPreservesBatchAndDoesNotIncreaseExistingFailureBackoff() throws Exception {
        linkedStart();status=503;response="{\"retry_in\":60}";CloudSync.runOnceForTest();JsonObject failed=received();
        assertEquals(1,CloudSync.failuresForTest());status=429;response="{\"error\":\"SYNC_LIMITER\",\"retry_in\":13}";
        assertTrue(finish(CloudSync.requestSync()).contains("rate limit"));assertEquals(failed,received());assertEquals(1,CloudSync.failuresForTest());
        assertTrue(CloudSync.scheduledDelayForTest()>11000&&CloudSync.scheduledDelayForTest()<=13000);
        status=200;response=linked("null",60);assertEquals("Up to date (settings v0).",finish(CloudSync.requestSync(System.nanoTime()+TimeUnit.SECONDS.toNanos(11))));
        assertEquals(failed,received());assertEquals(0,CloudSync.failuresForTest());
    }
    @Test void normalNetworkFailureStillReportsAndIncreasesTransportBackoff() throws Exception {
        linkedStart();status=503;response="{}";String reply=finish(CloudSync.requestSync());
        assertTrue(reply.startsWith("Cloud sync failed:"));assertEquals(1,CloudSync.failuresForTest());assertTrue(CloudSync.scheduledDelayForTest()>=4000);
    }
    @Test void runningRegularSyncCannotCancelQueuedManualSyncOrLeaveDuplicateTimers() throws Exception {
        linkedStart();entered=new CountDownLatch(1);release=new CountDownLatch(1);CloudSync.scheduleNowForTest();
        assertTrue(entered.await(3,TimeUnit.SECONDS));received();
        // Simulate an older acceptance timestamp while it waits behind the regular request.
        CloudSync.SyncRequest request=CloudSync.requestSync(System.nanoTime()-TimeUnit.SECONDS.toNanos(11));
        assertEquals("Syncing with Connection Guard Cloud…",request.initialMessage);assertFalse(request.completion.isDone());assertEquals(1,requests.get());
        release.countDown();assertEquals("Up to date (settings v0).",finish(request));received();assertEquals(2,requests.get());
        assertTrue(CloudSync.requestSync().initialMessage.startsWith("Please wait "));
        assertTrue(CloudSync.scheduledDelayForTest()>55000);assertNull(payloads.poll(200,TimeUnit.MILLISECONDS));
    }
    @Test void disableDuringManualSyncCompletesReplyAndCannotApplyRetiredConfig() throws Exception {
        linkedStart();response=linked(desired(9),60);entered=new CountDownLatch(1);release=new CountDownLatch(1);AtomicInteger reloads=new AtomicInteger();CloudSync.setReloadHook(reloads::incrementAndGet);
        CloudSync.SyncRequest request=CloudSync.requestSync();assertTrue(entered.await(3,TimeUnit.SECONDS));
        CloudSync.setDisabledByCommand(true);assertTrue(finish(request).contains("Cloud is off"));release.countDown();
        assertEquals(0,CloudManagedConfig.load(dir).version);assertEquals(0,reloads.get());assertFalse(CloudSync.isRunning());
    }
    @Test void translatedCommandMessagesAndHelpUseAllBundledLocales() {
        for(String locale:Arrays.asList("en","de","es")) {
            MessageCatalog m=MessageCatalog.defaults(locale);assertTrue(m.text("cloud.sync-applied",7).contains("7"));
            assertTrue(m.text("cloud.sync-wait",10).contains("10"));assertTrue(m.getStringList("messages.help").stream().anyMatch(l->l.contains("sync | settings")));
        }
    }
}
