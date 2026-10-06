package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.*;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.extensions.ExtensionRegistry;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real owned loopback HTTP; no external provider, account key or telemetry. */
@Timeout(10)
class GeneralFailoverRuntimeTest {
    private HttpServer server;
    private ExecutorService http;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile String fault = "none";
    private volatile boolean firstPositive;
    private volatile CountDownLatch held, entered;
    private final AtomicReference<String> keyedHeader = new AtomicReference<>();
    private Map<String, Object> values;

    private static void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle(), "Fixture transport or deadline still active.");
    }
    private static void retire() throws Exception {
        LookupRuntime prior = ConnectionGuard.getLookupRuntime();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            prior.retireIfIdle();
            if (!prior.isOpen() && prior.retireIfIdle()) return;
            Thread.sleep(2);
        }
        fail("Fixture runtime did not physically retire; outstanding work is never force-cancelled.");
    }
    @BeforeEach void setup() throws Exception {
        retire(); ConnectionGuard.configureLookup(LookupSettings.defaults());
        ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.setCacheProvider(new NoCacheProvider());
        ConnectionGuard.setRequiredPositiveFlags(1); ConnectionGuard.setLogger(null); ExtensionRegistry.closeAll();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http = Executors.newCachedThreadPool(); server.setExecutor(http);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String id = path.startsWith("/api/json/ip") ? "keyed" : path.split("/")[1];
            calls.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            if (id.equals("keyed")) keyedHeader.set(exchange.getRequestHeaders().getFirst("IPQS-KEY"));
            try {
                boolean primary = id.equals("custom-a") || id.equals("keyed");
                CountDownLatch wait = held;
                if (wait != null && (primary || fault.equals("parallel"))) {
                    entered.countDown(); assertTrue(wait.await(3, TimeUnit.SECONDS));
                }
                String current = primary ? fault : "none";
                if (current.equals("timeout")) Thread.sleep(1400);
                int status = current.equals("429") ? 429 : current.equals("503") ? 503 : 200;
                boolean positive = primary ? firstPositive : true;
                String body = id.equals("keyed") ? "{\"success\":true,\"vpn\":" + positive
                        + ",\"proxy\":false,\"tor\":false,\"fraud_score\":0}"
                        : "{\"vpn\":" + positive + "}";
                if (current.equals("malformed")) body = "{";
                if (current.equals("incomplete")) body = "{}";
                if (status == 429) exchange.getResponseHeaders().add("Retry-After", "3");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* Expected for the owned timeout request. */ }
            finally { exchange.close(); }
        });
        server.start();
        values = new HashMap<>(); values.put("provider.geo.service", "Disabled");
        values.put("lookup.deadline-ms", 3000); values.put("lookup.http-timeout-ms", 1000);
        values.put("lookup.circuit.failures", 1);
        custom("custom-a"); custom("custom-b"); custom("custom-c");
    }
    @AfterEach void stop() throws Exception {
        if (held != null) held.countDown();
        if (server != null) server.stop(0);
        if (http != null) { http.shutdownNow(); assertTrue(http.awaitTermination(3, TimeUnit.SECONDS)); }
        idle(); ConnectionGuard.setVpnProviders(new ArrayList<>()); ExtensionRegistry.closeAll();
        retire(); ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    private void custom(String id) {
        String base = "provider.vpn." + id + ".";
        values.put(base + "enabled", true);
        values.put(base + "request-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/" + id + "/%IP%");
        values.put(base + "response-format.is-vpn-field.field-name", "vpn");
    }
    private ProviderConfiguration draft(String... keys) { return new ProviderConfiguration(values::get, Arrays.asList(keys)); }
    private void activate(ProviderConfiguration draft) throws Exception { idle(); ConnectionGuard.applyProviders(draft); }
    private VpnResult lookup(String ip) throws Exception { return ConnectionGuard.getVpnResult(ip).get(3, TimeUnit.SECONDS); }
    private int calls(String id) { return calls.containsKey(id) ? calls.get(id).get() : 0; }

    @ParameterizedTest @ValueSource(strings = {"429", "503", "malformed", "incomplete", "timeout"})
    void customProvidersFailOverOnActualHttpFailuresAndStopAtTheFirstVerdict(String error) throws Exception {
        fault = error; activate(draft("custom-a", "custom-b", "custom-c"));
        VpnResult result = lookup("192.0.2.91");
        assertEquals(ProviderVote.Status.POSITIVE, result.getStatus());
        assertEquals(2, result.getVotes().size()); assertEquals(ProviderVote.Status.UNKNOWN, result.getVotes().get(0).getStatus());
        assertEquals(1, calls("custom-a")); assertEquals(1, calls("custom-b")); assertEquals(0, calls("custom-c"));
    }
    @Test void aPendingConcreteNegativeNeverContactsAnotherProvider() throws Exception {
        held = new CountDownLatch(1); entered = new CountDownLatch(1);
        activate(draft("custom-a", "custom-b"));
        CompletableFuture<VpnResult> query = ConnectionGuard.getVpnResult("192.0.2.92");
        assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(0, calls("custom-b"));
        held.countDown(); assertEquals(ProviderVote.Status.NEGATIVE, query.get(2, TimeUnit.SECONDS).getStatus());
        assertEquals(0, calls("custom-b"));
    }
    @Test void aKeyedNativeProviderUsesTheSameChainWithoutProxyCheck() throws Exception {
        values.put("provider.vpn.ipqualityscore.enabled", true); values.put("provider.vpn.ipqualityscore.api-key", "synthetic-key");
        ProviderConfiguration draft = draft("ipqualityscore", "custom-b");
        // Only the existing package-private transport seam differs from production.
        draft.providers.set(0, new IpQualityScoreVpnProvider("synthetic-key", 0, true, true,
                HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/api/json/ip")));
        firstPositive = true; activate(draft);
        assertTrue(lookup("192.0.2.93").isVpn()); assertEquals("synthetic-key", keyedHeader.get());
        assertEquals(1, calls("keyed")); assertEquals(0, calls("custom-b"));
        fault = "429"; assertTrue(lookup("192.0.2.94").isVpn());
        assertEquals(2, calls("keyed")); assertEquals(1, calls("custom-b"));
    }
    @Test void disablingTheChainRestoresConcurrentVotingAndTheOriginalThreshold() throws Exception {
        values.put("provider.vpn-failover.enabled", false); values.put("required-positive-flags", 2);
        fault = "parallel"; held = new CountDownLatch(1); entered = new CountDownLatch(2);
        activate(draft("custom-a", "custom-b"));
        CompletableFuture<VpnResult> query = ConnectionGuard.getVpnResult("192.0.2.95");
        assertTrue(entered.await(1, TimeUnit.SECONDS), "Both legacy votes must be dispatched while the first is held.");
        held.countDown(); VpnResult result = query.get(2, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.NEGATIVE, result.getStatus()); assertEquals(2, result.getPositiveThreshold());
        assertEquals(2, result.getVotes().size()); assertEquals(1, calls("custom-a")); assertEquals(1, calls("custom-b"));
    }
    @Test void switchingModeInvalidatesEarlierNegativeFactsAndBadReloadPreservesTheNewMode() throws Exception {
        ConnectionGuard.setCacheProvider(new MemoryCacheProvider());
        activate(draft("custom-a", "custom-b")); assertFalse(lookup("192.0.2.96").isVpn());
        assertTrue(lookup("192.0.2.96").isFromCache()); assertEquals(0, calls("custom-b"));
        values.put("provider.vpn-failover.enabled", false);
        ProviderConfiguration parallel = draft("custom-a", "custom-b"); activate(parallel);
        VpnResult positive = lookup("192.0.2.96"); assertTrue(positive.isVpn()); assertFalse(positive.isFromCache());
        values.put("provider.vpn-failover.order", Arrays.asList("custom-a", "custom-a"));
        assertThrows(IllegalArgumentException.class, () -> activate(draft("custom-a", "custom-b")));
        assertSame(parallel, ConnectionGuard.getActiveDraft()); assertTrue(lookup("192.0.2.97").isVpn());
    }
    @Test void reorderingAndDisablingDoNotResetSourceQuotaUsage() throws Exception {
        firstPositive = true;
        for (String key : Arrays.asList("custom-a", "custom-b")) values.put("provider.vpn." + key + ".daily-budget", 1);
        activate(draft("custom-a", "custom-b")); assertTrue(lookup("192.0.2.101").isVpn());
        values.put("provider.vpn-failover.order", Arrays.asList("custom-b", "custom-a"));
        activate(draft("custom-a", "custom-b")); assertTrue(lookup("192.0.2.102").isVpn());
        values.put("provider.vpn-failover.order", Arrays.asList("custom-a", "custom-b"));
        activate(draft("custom-a", "custom-b")); assertEquals(ProviderVote.Status.UNKNOWN, lookup("192.0.2.103").getStatus());
        values.put("provider.vpn-failover.enabled", false);
        activate(draft("custom-a", "custom-b")); assertEquals(ProviderVote.Status.UNKNOWN, lookup("192.0.2.104").getStatus());
        assertEquals(1, calls("custom-a")); assertEquals(1, calls("custom-b"));
        assertTrue(ConnectionGuard.providerHealth().containsKey("vpn.custom-a"));
        assertTrue(ConnectionGuard.providerHealth().containsKey("vpn.custom-b"));
    }
    @Test void aStrictAttemptLimitCanStillSkipAnAlreadyOpenCircuitLocally() throws Exception {
        fault = "429"; values.put("provider.max-external-attempts", 1);
        activate(draft("custom-a", "custom-b"));
        assertEquals(ProviderVote.Status.UNKNOWN, lookup("192.0.2.105").getStatus()); assertEquals(0, calls("custom-b"));
        assertTrue(lookup("192.0.2.106").isVpn());
        assertEquals(1, calls("custom-a")); assertEquals(1, calls("custom-b"));
    }
    @Test void sharedIpv6CallersCoalesceAcrossAllVisitedCustomProviders() throws Exception {
        fault = "429"; held = new CountDownLatch(1); entered = new CountDownLatch(1);
        activate(draft("custom-a", "custom-b")); List<CompletableFuture<VpnResult>> clients = new ArrayList<>();
        for (int i = 0; i < 100; i++) clients.add(ConnectionGuard.getVpnResult(i % 2 == 0 ? "2001:db8::a" : "2001:db8:0:0:0:0:0:a"));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(0, calls("custom-b")); held.countDown();
        for (CompletableFuture<VpnResult> client : clients) assertTrue(client.get(3, TimeUnit.SECONDS).isVpn());
        assertEquals(1, calls("custom-a")); assertEquals(1, calls("custom-b"));
    }
    @Test void aNonVotingExtensionCannotStopTheChainOrSupplyAThresholdVote() throws Exception {
        ConnectionGuardApi.registerProvider(new ProviderDescriptor("enrich", "1", String.join("", Collections.nCopies(64, "a")), false),
                ip -> CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty())));
        values.put("integrations.providers.enabled", true);
        Map<String, Object> selected = new HashMap<>(); selected.put("id", "enrich"); selected.put("voting", false);
        values.put("integrations.providers.sources", Collections.singletonList(selected));
        values.put("provider.vpn-failover.order", Collections.singletonList("extension.enrich"));
        activate(draft("custom-a")); VpnResult result = lookup("192.0.2.107");
        assertEquals(ProviderVote.Status.NEGATIVE, result.getStatus()); assertEquals(2, result.getVotes().size());
        assertFalse(result.getVotes().get(0).isVoting()); assertEquals(ProviderVote.Status.POSITIVE, result.getVotes().get(0).getStatus());
        assertEquals(1, calls("custom-a"));
    }
}
