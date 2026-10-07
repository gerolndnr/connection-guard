package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import com.google.gson.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native provider HTTP against an owned loopback service; zero public requests. */
@Timeout(15)
class KeylessFailoverRuntimeTest {
    private HttpServer server;
    private ExecutorService workers;
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, Integer> codes = new ConcurrentHashMap<>();
    private volatile CountDownLatch entered, release;
    private volatile String delayed;
    private Map<String, Object> values;
    private com.github.gerolndnr.connectionguard.core.local.IntelSnapshot confirmationIntel;
    private static void idle() throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime()<deadline) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
    }
    @BeforeEach void setup() throws Exception {
        idle(); ConnectionGuard.setLogger(null); ConnectionGuard.setCacheProvider(new NoCacheProvider());
        Map<String, Object> empty = new HashMap<>(); empty.put("provider.geo.service", "Disabled");
        ConnectionGuard.applyProviders(new ProviderConfiguration(empty::get, Collections.emptyList()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String id = exchange.getRequestURI().getPath().split("/")[1]; calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            try {
                if (id.equals(delayed)) { if (entered != null) entered.countDown(); if (release != null) assertTrue(release.await(3, TimeUnit.SECONDS)); else Thread.sleep(400); }
                int code = codes.getOrDefault(id, 200);
                if (code == 429) exchange.getResponseHeaders().add("Retry-After", "7");
                if (code == 302) exchange.getResponseHeaders().add("Location", "/unexpected");
                byte[] data = answers.getOrDefault(id, "invalid").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, data.length); exchange.getResponseBody().write(data);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException timedOut) { /* An owned timed-out request may close its transport. */ }
            finally { exchange.close(); }
        }); server.start();
        answers.put("blackbox", "N"); answers.put("ipcheck", "TRUE");
        answers.put("zowi", "{\"security\":{\"vpn\":{\"detected\":true},\"proxy\":false,\"tor\":false,\"hosting\":false}}");
        values = new HashMap<>(); values.put("provider.geo.service", "Disabled");
        for (String id : Arrays.asList("blackbox", "ipcheck", "zowi")) values.put("provider.vpn."+id+".enabled", true);
        values.put("provider.vpn-failover.order", Arrays.asList("blackbox", "ipcheck", "zowi"));
        // Explicit compatibility mode for the original aggregate-list transport regressions.
        values.put("provider.vpn.blackbox.require-confirmation", false);
    }
    @AfterEach void stop() throws Exception {
        if (release != null) release.countDown(); server.stop(0); workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        idle(); Map<String, Object> empty = new HashMap<>(); empty.put("provider.geo.service", "Disabled");
        ConnectionGuard.applyProviders(new ProviderConfiguration(empty::get, Collections.emptyList()));
    }
    private void activate() throws Exception {
        idle(); ProviderConfiguration draft = new ProviderConfiguration(values::get, Arrays.asList("blackbox", "ipcheck", "zowi"));
        for (int i = 0; i < draft.providers.size(); i++) {
            String id = draft.keys.get(i); HttpUrl endpoint = HttpUrl.get("http://127.0.0.1:"+server.getAddress().getPort()+"/"+id);
            draft.providers.set(i, id.equals("blackbox") ? new BlackboxVpnProvider(!Boolean.FALSE.equals(values.get("provider.vpn.blackbox.require-confirmation")),confirmationIntel==null?draft.intelSnapshot:confirmationIntel,endpoint) : id.equals("ipcheck") ? new IpCheckVpnProvider(endpoint) : new ZowiVpnProvider(endpoint));
        }
        ConnectionGuard.applyProviders(draft);
    }
    private int calls(String id) { return calls.containsKey(id) ? calls.get(id).get() : 0; }
    private VpnResult query(String ip) throws Exception { return ConnectionGuard.getVpnResult(ip).get(4, TimeUnit.SECONDS); }
    @ParameterizedTest @ValueSource(strings = {"E", "garbage", "429", "503", "302", "oversized"})
    void nativeTextFailuresAdvanceTheChainWithoutAFalseCleanAnswer(String fault) throws Exception {
        if (fault.matches("[0-9]+")) codes.put("blackbox", Integer.parseInt(fault));
        else answers.put("blackbox", fault.equals("oversized") ? String.join("", Collections.nCopies(262145, "N")) : fault);
        activate(); VpnResult result = query("192.0.2.41");
        assertTrue(result.isVpn()); assertEquals(ProviderVote.Status.UNKNOWN, result.getVotes().get(0).getStatus());
        assertEquals(1, calls("blackbox")); assertEquals(1, calls("ipcheck")); assertEquals(0, calls("zowi")); assertEquals(0, calls("unexpected"));
        if (fault.equals("429")) { assertEquals(FailureReason.RATE_LIMIT, result.getVotes().get(0).getReason()); assertTrue(ConnectionGuard.providerHealth().get("vpn.blackbox").snapshot().paused); }
    }
    @Test void anActualNegativeStopsWithoutSendingTheIpToMoreRecipients() throws Exception {
        activate(); assertEquals(ProviderVote.Status.NEGATIVE, query("192.0.2.42").getStatus());
        assertEquals(1, calls("blackbox")); assertEquals(0, calls("ipcheck")); assertEquals(0, calls("zowi"));
    }
    @Test void explicitLegacyModeKeepsFiveRecapturedIpQueryVpnMissesPositive() throws Exception {
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/providers/keyless-vpn-recapture.json"), StandardCharsets.UTF_8)) {
            JsonArray fixtures = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases"); assertEquals(5, fixtures.size());
            activate();
            for (JsonElement element : fixtures) {
                JsonObject fixture = element.getAsJsonObject(), recorded = fixture.getAsJsonObject("responses"); String ip = fixture.get("ip").getAsString();
                assertFalse(IpQueryVpnProvider.parse(ip, recorded.getAsJsonObject("ipquery")).get().isVpn(), fixture.get("id").getAsString());
                answers.put("blackbox", recorded.get("blackbox").getAsString());
                VpnResult verdict = query(ip); assertTrue(verdict.isVpn(), fixture.get("id").getAsString());
                assertEquals("blackbox", verdict.getVotes().get(0).getProvider()); assertEquals(1, verdict.getVotes().size());
            }
            assertEquals(5, calls("blackbox")); assertEquals(0, calls("ipcheck")); assertEquals(0, calls("zowi"));
        }
    }
    @Test void hostingOnlyRemainsReviewAndContinuesToAConcreteFallback() throws Exception {
        values.put("provider.vpn-failover.order", Arrays.asList("zowi", "blackbox", "ipcheck"));
        answers.put("zowi", "{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"hosting\":true}}");
        answers.put("blackbox", "Y"); activate(); VpnResult result = query("192.0.2.43");
        assertTrue(result.isVpn()); assertEquals(ProviderVote.Status.UNKNOWN, result.getVotes().get(0).getStatus());
        assertEquals(Boolean.TRUE, result.getVotes().get(0).getDetails().get(DetectionDetails.Type.HOSTING));
        assertEquals(BlackboxVpnProvider.LISTED_REASON, result.getVpnProviderName().get()); assertEquals(0, calls("ipcheck"));
    }
    @ParameterizedTest @ValueSource(strings = {"blackbox", "ipcheck", "zowi"})
    void sixtyActualRequestsAreCappedAndTheNextLoginUsesFailover(String first) throws Exception {
        List<String> order = new ArrayList<>(Arrays.asList("blackbox", "ipcheck", "zowi")); order.remove(first); order.add(0, first);
        values.put("provider.vpn-failover.order", order); answers.put("blackbox", "Y");
        answers.put(first, first.equals("blackbox") ? "N" : first.equals("ipcheck") ? "FALSE"
                : "{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"hosting\":false}}");
        activate();
        for (int i = 1; i <= 60; i++) assertFalse(query("198.51.100."+i).isVpn());
        VpnResult overflow = query("198.51.100.61"); assertTrue(overflow.isVpn());
        assertEquals(FailureReason.BUDGET_EXHAUSTED, overflow.getVotes().get(0).getReason());
        assertEquals(60, calls(first)); assertEquals(1, calls(order.get(1)));
        values.put("provider.vpn-failover.order", Arrays.asList(order.get(2), first, order.get(1)));
        values.put("provider.vpn."+order.get(2)+".enabled", false); activate();
        assertTrue(query("198.51.100.62").isVpn()); assertEquals(60, calls(first)); assertEquals(2, calls(order.get(1)));
    }
    @Test void oneHundredConcurrentLoginsShareOnePhysicalNativeRequest() throws Exception {
        delayed = "blackbox"; entered = new CountDownLatch(1); release = new CountDownLatch(1); answers.put("blackbox", "Y"); activate();
        List<CompletableFuture<VpnResult>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) results.add(ConnectionGuard.getVpnResult("2001:db8::48"));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(1, calls("blackbox")); release.countDown();
        for (CompletableFuture<VpnResult> result : results) assertTrue(result.get(3, TimeUnit.SECONDS).isVpn());
        assertEquals(1, calls("blackbox")); assertEquals(0, calls("ipcheck"));
    }
    @Test void attemptLimitAndWholeLoginDeadlinePreventExtraRecipients() throws Exception {
        answers.put("blackbox", "E"); values.put("provider.max-external-attempts", 1); activate();
        assertEquals(ProviderVote.Status.UNKNOWN, query("192.0.2.44").getStatus()); assertEquals(0, calls("ipcheck"));
        values.put("provider.max-external-attempts", 16); values.put("lookup.deadline-ms", 150); values.put("lookup.http-timeout-ms", 150); delayed = "blackbox"; activate();
        long start = System.nanoTime(); VpnResult timedOut = query("192.0.2.45");
        assertEquals(ProviderVote.Status.UNKNOWN, timedOut.getStatus()); assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<600);
        assertEquals(0, calls("ipcheck")); assertEquals(0, calls("zowi"));
    }
    @Test void rejectedReloadKeepsTheActivatedProvidersAndOrder() throws Exception {
        activate(); ProviderConfiguration active = ConnectionGuard.getActiveDraft();
        values.put("provider.vpn-failover.order", Arrays.asList("blackbox", "blackbox"));
        assertThrows(IllegalArgumentException.class, this::activate); assertSame(active, ConnectionGuard.getActiveDraft());
        assertFalse(query("192.0.2.46").isVpn()); assertEquals(0, calls("ipcheck"));
    }
    private void confirmation(boolean enabled) {
        values.put("provider.vpn.blackbox.require-confirmation",enabled);
        values.put("provider.vpn.ipcheck.enabled",false);
        values.put("provider.vpn-failover.order",Arrays.asList("blackbox","zowi"));
        answers.put("blackbox","Y");
    }
    private void cleanZowi() { answers.put("zowi","{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"hosting\":false}}"); }
    @Test void unconfirmedConsumerListingAllowsTheNextNormalNegativeWithoutExtraRequests() throws Exception {
        confirmation(true);cleanZowi();activate();VpnResult result=query("198.51.100.222");
        assertEquals(ProviderVote.Status.NEGATIVE,result.getStatus());
        assertEquals(FailureReason.NO_EVIDENCE,result.getVotes().get(0).getReason());
        assertEquals(1,calls("blackbox"));assertEquals(1,calls("zowi"));assertEquals(0,calls("ipcheck"));
        assertEquals(1,ConnectionGuard.providerHealth().get("vpn.blackbox").snapshot().dailyUsed);
        assertEquals(1,ConnectionGuard.providerHealth().get("vpn.zowi").snapshot().dailyUsed);
    }
    @Test void unconfirmedListingAllowsTheNextNormalPositiveToRefuse() throws Exception {
        confirmation(true);activate();VpnResult result=query("198.51.100.222");
        assertTrue(result.isVpn());assertEquals(ProviderVote.Status.UNKNOWN,result.getVotes().get(0).getStatus());
        assertEquals("zowi",result.getVotes().get(1).getProvider());assertEquals(1,calls("blackbox"));assertEquals(1,calls("zowi"));
    }
    @Test void freshHostingMembershipConfirmsListingAndBoundsCachedEvidenceLifetime() throws Exception {
        confirmation(true);long now=System.currentTimeMillis();
        confirmationIntel=com.github.gerolndnr.connectionguard.core.local.IntelFixtures.hosting(now-1000,now);
        activate();VpnResult result=query("203.0.113.2");assertTrue(result.isVpn());assertEquals(1,result.getVotes().size());
        assertEquals(confirmationIntel.validUntil(),result.getVotes().get(0).getValidUntil());
        assertEquals(confirmationIntel.generation,result.getVotes().get(0).getSourceVersion());
        assertEquals(ProviderVote.Status.UNKNOWN,LookupFreshness.vpn(result,confirmationIntel.validUntil()).getStatus());
        assertEquals(1,calls("blackbox"));assertEquals(0,calls("zowi"));
    }
    @Test void staleHostingMembershipCannotConfirmAnAddress() throws Exception {
        confirmation(true);cleanZowi();long now=System.currentTimeMillis();
        confirmationIntel=com.github.gerolndnr.connectionguard.core.local.IntelFixtures.hosting(now-TimeUnit.HOURS.toMillis(73),now);
        activate();assertFalse(query("203.0.113.2").isVpn());assertEquals(1,calls("zowi"));
    }
    @Test void defaultConfirmationLeavesBlackboxNegativeDecisive() throws Exception {
        confirmation(true);answers.put("blackbox","N");activate();assertFalse(query("198.51.100.222").isVpn());
        assertEquals(1,calls("blackbox"));assertEquals(0,calls("zowi"));
    }
    @Test void optingOutRestoresAggregateBlockingWithoutConfirmation() throws Exception {
        confirmation(false);cleanZowi();activate();assertTrue(query("198.51.100.222").isVpn());
        assertEquals(1,calls("blackbox"));assertEquals(0,calls("zowi"));
    }
    @Test void unconfirmedBurstCoalescesBothNormalProviderRequests() throws Exception {
        confirmation(true);cleanZowi();delayed="blackbox";entered=new CountDownLatch(1);release=new CountDownLatch(1);activate();
        List<CompletableFuture<VpnResult>> burst=new ArrayList<>();
        for(int i=0;i<100;i++)burst.add(ConnectionGuard.getVpnResult("198.51.100.222"));
        assertTrue(entered.await(1,TimeUnit.SECONDS));release.countDown();
        for(CompletableFuture<VpnResult> result:burst)assertEquals(ProviderVote.Status.NEGATIVE,result.get(3,TimeUnit.SECONDS).getStatus());
        assertEquals(1,calls("blackbox"));assertEquals(1,calls("zowi"));
    }

}
