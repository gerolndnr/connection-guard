package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.geo.ProxyCheckGeoProvider;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Physical HTTP and physical quota accounting, including the two concurrent login scopes. */
@Timeout(10)
class ProxyCheckSharedRuntimeTest {
    HttpServer server; ExecutorService http; ProxyCheckClient client;
    final AtomicInteger requests = new AtomicInteger(), fallback = new AtomicInteger();
    final AtomicReference<URI> uri = new AtomicReference<>();
    volatile CountDownLatch held, entered;
    volatile int status = 200; volatile boolean country = true, malformed, hosting;
    private void idle() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < end) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
    }
    @BeforeEach void start() throws Exception {
        idle(); ConnectionGuard.configureLookup(LookupSettings.defaults());
        ConnectionGuard.setRequiredPositiveFlags(1); ConnectionGuard.setCacheProvider(new NoCacheProvider()); ConnectionGuard.setLogger(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http = Executors.newCachedThreadPool(); server.setExecutor(http);
        server.createContext("/v2/", e -> {
            requests.incrementAndGet(); uri.set(e.getRequestURI());
            try {
                if (held != null) { entered.countDown(); if (!held.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Owned fixture not released"); }
                String ip = e.getRequestURI().getPath().substring(4);
                String body = malformed ? "{}" : "{\"status\":\"ok\",\"" + ip + "\":{\"proxy\":\"yes\",\"type\":\"" + (hosting ? "Hosting" : "VPN")
                        + "\",\"asn\":\"AS64496\",\"provider\":\"Fixture ISP\",\"risk\":0" + (country ? ",\"isocode\":\"PT\"" : "") + "}}";
                byte[] data = body.getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(status, data.length); e.getResponseBody().write(data);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* Owned timeout client can close. */ }
            finally { e.close(); }
        }); server.start();
        client = new ProxyCheckClient("synthetic-key", HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/"));
        // Retained v3 configuration must still request the supported v2 VPN signal.
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new ProxyCheckVpnProvider("synthetic-key", true, client))));
        ConnectionGuard.setGeoProvider(new ProxyCheckGeoProvider("synthetic-key", true, client));
    }
    @AfterEach void stop() throws Exception {
        if (held != null) held.countDown(); server.stop(0); http.shutdownNow(); assertTrue(http.awaitTermination(3, TimeUnit.SECONDS));
        idle(); ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.setGeoProvider(null);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eitherScopeCanStartFirstAndUsesOneRequestAndOneQuotaSlot(boolean geoFirst) throws Exception {
        ConnectionGuard.setProviderBudget("ProxyCheck", 1, 0);
        String ip = "2001:db8:0:0:0:0:0:7";
        GeoLookup geo = geoFirst ? ConnectionGuard.getGeoLookup(ip).get(3, TimeUnit.SECONDS) : null;
        assertTrue(ConnectionGuard.getVpnResult(ip).get(3, TimeUnit.SECONDS).isVpn());
        if (!geoFirst) geo = ConnectionGuard.getGeoLookup(ip).get(3, TimeUnit.SECONDS);
        assertEquals("PT", geo.getResult().get().getCountryName());
        assertEquals(Long.valueOf(64496), geo.getResult().get().getAsn());
        assertEquals(1, requests.get());
        String query = uri.get().getQuery();
        assertEquals("/v2/2001:db8::7", uri.get().getPath()); assertTrue(query.contains("vpn=1")); assertTrue(query.contains("asn=1"));
        ProviderHealth.Snapshot health = ConnectionGuard.providerHealth().get("ProxyCheck").snapshot();
        assertEquals(1, health.dailyUsed); assertEquals(1, health.attempts); assertEquals(1, health.successes);
        assertEquals(FailureReason.BUDGET_EXHAUSTED, ConnectionGuard.getVpnResult("192.0.2.2").get(3, TimeUnit.SECONDS).getVotes().get(0).getReason());
        assertEquals(FailureReason.BUDGET_EXHAUSTED, ConnectionGuard.getGeoLookup("192.0.2.2").get(3, TimeUnit.SECONDS).getReason());
        assertEquals(1, requests.get());
    }
    @Test void oneHundredConcurrentDualScopeLoginsShareOnePhysicalRequest() throws Exception {
        held = new CountDownLatch(1); entered = new CountDownLatch(1);
        List<CompletableFuture<VpnResult>> vpn = new ArrayList<>(); List<CompletableFuture<GeoLookup>> geo = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            vpn.add(ConnectionGuard.getVpnResult("192.0.2.7")); geo.add(ConnectionGuard.getGeoLookup("192.0.2.7"));
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(1, requests.get());
        vpn.get(0).cancel(false); held.countDown();
        for (int i = 1; i < vpn.size(); i++) assertTrue(vpn.get(i).get(3, TimeUnit.SECONDS).isVpn());
        for (CompletableFuture<GeoLookup> result : geo) assertTrue(result.get(3, TimeUnit.SECONDS).getResult().isPresent());
        assertEquals(1, requests.get()); assertEquals(1, ConnectionGuard.providerHealth().get("ProxyCheck").snapshot().dailyUsed);
    }
    @Test void fastHttpFailureIsSharedAndVpnStillUsesTheConfiguredFallback() throws Exception {
        status = 503;
        VpnProvider other = new VpnProvider() {
            public String sourceName() { return "fixture.fallback"; }
            public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
                fallback.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true)));
            }
        };
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(new ProxyCheckVpnProvider("synthetic-key", true, client), other)));
        ConnectionGuard.setGeoProvider(new ProxyCheckGeoProvider("synthetic-key", true, client)); ConnectionGuard.setFailover(true, 16);
        VpnResult vpn = ConnectionGuard.getVpnResult("192.0.2.8").get(3, TimeUnit.SECONDS);
        assertTrue(vpn.isVpn()); assertEquals(FailureReason.HTTP_ERROR, vpn.getVotes().get(0).getReason());
        assertEquals(FailureReason.HTTP_ERROR, ConnectionGuard.getGeoLookup("192.0.2.8").get(3, TimeUnit.SECONDS).getReason());
        assertEquals(1, requests.get()); assertEquals(1, fallback.get());
        assertEquals(1, ConnectionGuard.providerHealth().get("ProxyCheck").snapshot().dailyUsed);
    }
    @Test void noCacheMakesTheNextVpnOnlyLookupFresh() throws Exception {
        assertTrue(ConnectionGuard.getVpnResult("192.0.2.9").get(3, TimeUnit.SECONDS).isVpn());
        hosting = true;
        assertFalse(ConnectionGuard.getVpnResult("192.0.2.9").get(3, TimeUnit.SECONDS).isVpn());
        assertEquals(2, requests.get());
    }
    @Test void missingCountryDoesNotEraseAnExplicitVpnOrMakeAnExtraGeoRequest() throws Exception {
        country = false;
        assertTrue(ConnectionGuard.getVpnResult("192.0.2.10").get(3, TimeUnit.SECONDS).isVpn());
        assertEquals(FailureReason.INVALID_RESPONSE, ConnectionGuard.getGeoLookup("192.0.2.10").get(3, TimeUnit.SECONDS).getReason());
        assertEquals(1, requests.get());
    }
    @Test void malformedSharedAnswerIsUnknownInBothScopesAndNeverDoubleCountsQuota() throws Exception {
        malformed = true;
        assertEquals(FailureReason.INVALID_RESPONSE, ConnectionGuard.getVpnResult("192.0.2.11").get(3, TimeUnit.SECONDS).getVotes().get(0).getReason());
        assertEquals(FailureReason.INVALID_RESPONSE, ConnectionGuard.getGeoLookup("192.0.2.11").get(3, TimeUnit.SECONDS).getReason());
        assertEquals(1, requests.get()); assertEquals(1, ConnectionGuard.providerHealth().get("ProxyCheck").snapshot().dailyUsed);
    }
    @Test void configurationsShareOnlyWhenProxyCheckVpnIsEnabled() {
        Map<String, Object> values = new HashMap<>(); values.put("provider.geo.service", "ProxyCheck"); values.put("provider.vpn.proxycheck.api-version", "v3");
        values.put("provider.vpn.proxycheck.enabled", true);
        ProviderConfiguration both = new ProviderConfiguration(values::get, Collections.singletonList("proxycheck"));
        assertTrue(((ProxyCheckGeoProvider) both.geo).sharesVpnQuery());
        values.put("provider.vpn.proxycheck.enabled", false);
        ProviderConfiguration onlyGeo = new ProviderConfiguration(values::get, Collections.singletonList("proxycheck"));
        assertFalse(((ProxyCheckGeoProvider) onlyGeo.geo).sharesVpnQuery());
    }
    @Test void pendingFlightsAreBoundedAndCannotBeEvictedByNewAddresses() throws Exception {
        CompletableFuture<Optional<com.google.gson.JsonObject>> upstream = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();
        java.util.function.Function<Runnable, CompletableFuture<Optional<com.google.gson.JsonObject>>> operation = sent -> {
            started.incrementAndGet(); sent.run(); return upstream;
        };
        CompletableFuture<?> vpn = client.query("192.0.2.20", false, 1, 5000, operation, () -> {});
        CompletableFuture<?> geo = client.query("192.0.2.20", true, 1, 5000, operation, () -> {});
        CompletableFuture<?> rejected = client.query("192.0.2.21", false, 1, 5000, operation, () -> {});
        assertEquals(FailureReason.OVERLOADED, LookupException.reason(assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS))));
        assertEquals(1, started.get()); vpn.cancel(false); assertFalse(upstream.isCancelled());
        upstream.complete(Optional.of(new com.google.gson.JsonObject())); geo.get(1, TimeUnit.SECONDS);
        client.query("192.0.2.21", false, 1, 5000, operation, () -> {}).get(1, TimeUnit.SECONDS);
        assertEquals(2, started.get());
    }
    @Test void completedHandoffsExpireAndDoNotReuseOldAddressesBeyondTheLoginDeadline() throws Exception {
        AtomicInteger started = new AtomicInteger();
        java.util.function.Function<Runnable, CompletableFuture<Optional<com.google.gson.JsonObject>>> operation = sent -> {
            started.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new com.google.gson.JsonObject()));
        };
        client.query("192.0.2.22", false, 1, 1, operation, () -> {}).get(1, TimeUnit.SECONDS);
        Thread.sleep(5);
        client.query("192.0.2.22", true, 1, 1, operation, () -> {}).get(1, TimeUnit.SECONDS);
        assertEquals(2, started.get());
    }
}
