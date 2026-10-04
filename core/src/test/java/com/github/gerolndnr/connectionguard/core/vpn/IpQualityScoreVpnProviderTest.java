package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.Request;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fabricated source data, owned loopback HTTP. Never sends an operator key or contacts IPQS. */
@Timeout(8)
class IpQualityScoreVpnProviderTest {
    private HttpServer server;
    private volatile String body = "{\"success\":true,\"vpn\":false,\"proxy\":false,\"tor\":false,\"fraud_score\":79.999,\"ASN\":64500,\"ISP\":\"Synthetic ISP\",\"country_code\":\"PT\"}";
    private volatile int status = 200;
    private volatile int delay;
    private volatile CountDownLatch release;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpUrl> captured = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private IpQualityScoreVpnProvider provider;
    private static void idle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle(), "Physical worker has not returned.");
    }
    @BeforeEach void initialize() throws Exception {
        idle(); ConnectionGuard.applySettings(GuardSettings.defaults());
        ConnectionGuard.setLogger(null); ConnectionGuard.setCacheProvider(new NoCacheProvider());
        ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.setRequiredPositiveFlags(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/json/ip", exchange -> {
            calls.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("IPQS-KEY"));
            captured.set(HttpUrl.get("http://127.0.0.1" + exchange.getRequestURI())); entered.countDown();
            try {
                CountDownLatch gate = release;
                if (gate != null && !gate.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out.");
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().add("Retry-After", "2");
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/json/ip");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* Expected in timeout tests. */ }
            finally { exchange.close(); }
        });
        server.start();
        provider = new IpQualityScoreVpnProvider("synthetic-header-key", 0, true, true,
                HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/api/json/ip"));
    }
    @AfterEach void stop() throws Exception {
        if (release != null) release.countDown(); server.stop(0); idle();
        ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.applySettings(GuardSettings.defaults());
        ProviderHttp.configure(LookupSettings.defaults());
    }
    private VpnResult query(String ip) throws Exception { return provider.getVpnResult(ip).get(4, TimeUnit.SECONDS).get(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private void select() { ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(provider))); }
    @Test void productionRequestUsesOnlyFixedHttpsAndHeaderAuthentication() {
        Request request = new IpQualityScoreVpnProvider("synthetic-header-key", 1, false, false).request("::ffff:192.0.2.1");
        assertEquals("https", request.url().scheme()); assertEquals("ipqualityscore.com", request.url().host());
        assertEquals("/api/json/ip", request.url().encodedPath()); assertEquals("192.0.2.1", request.url().queryParameter("ip"));
        assertEquals("1", request.url().queryParameter("strictness")); assertEquals("false", request.url().queryParameter("fast"));
        assertEquals("false", request.url().queryParameter("allow_public_access_points"));
        assertEquals("synthetic-header-key", request.header("IPQS-KEY"));
        assertEquals(new HashSet<>(Arrays.asList("ip", "strictness", "allow_public_access_points", "fast")), request.url().queryParameterNames());
        assertFalse(request.url().toString().contains("synthetic-header-key")); assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> new IpQualityScoreVpnProvider("synthetic-header-key", 0, true, true, HttpUrl.get("https://example.invalid/api/json/ip")));
    }
    @Test void actualHttpPreservesIpv6AndDecimalsWithoutInventedOperatorOrHosting() throws Exception {
        VpnResult result = query("2001:db8::5");
        assertEquals(Exemptions.normalize("2001:db8::5"), captured.get().queryParameter("ip"));
        assertEquals("synthetic-header-key", key.get()); assertFalse(captured.get().toString().contains(key.get()));
        assertFalse(result.isVpn()); assertEquals(new BigDecimal("79.999"), result.getDetails().getExactRisk());
        assertNull(result.getDetails().getRisk()); assertEquals(Long.valueOf(64500), result.getDetails().getAsn());
        assertNull(result.getDetails().getOperator()); assertNull(result.getDetails().get(DetectionDetails.Type.HOSTING));
        assertNull(result.getDetails().getConfidence()); assertEquals("PT", result.getDetails().getCountry());
        assertTrue(result.getDetails().describe().contains("risk=79.999"));
    }
    @Test void documentedUnknownValuesStayUnknownAndHighRiskDoesNotCreateAVpnVote() {
        VpnResult result = IpQualityScoreVpnProvider.parse("192.0.2.1", json("{\"success\":true,\"vpn\":false,\"proxy\":false,\"tor\":false,\"fraud_score\":100,\"ASN\":null,\"ISP\":\"N/A\",\"country_code\":\"N/A\",\"organization\":\"Synthetic owner\",\"connection_type\":\"Data Center\"}")).get();
        assertFalse(result.isVpn()); assertEquals(Integer.valueOf(100), result.getDetails().getRisk());
        assertNull(result.getDetails().getAsn()); assertNull(result.getDetails().getIsp()); assertNull(result.getDetails().getCountry());
        assertNull(result.getDetails().getOperator()); assertNull(result.getDetails().get(DetectionDetails.Type.HOSTING));
        JsonObject absent = json(body); absent.remove("fraud_score");
        assertNull(IpQualityScoreVpnProvider.parse("192.0.2.1", absent).get().getDetails().getExactRisk());
        absent.addProperty("fraud_score", 0); assertEquals(BigDecimal.ZERO, IpQualityScoreVpnProvider.parse("192.0.2.1", absent).get().getDetails().getExactRisk());
    }
    @ParameterizedTest @ValueSource(strings={"vpn", "proxy", "tor"})
    void individualSourceClassificationsRemainSeparate(String type) {
        JsonObject value = json(body); value.addProperty(type, true); value.addProperty("proxy", true);
        VpnResult result = IpQualityScoreVpnProvider.parse("192.0.2.1", value).get(); assertTrue(result.isVpn());
        assertEquals(Boolean.valueOf(type.equals("vpn")), result.getDetails().get(DetectionDetails.Type.VPN));
        assertEquals(Boolean.valueOf(type.equals("tor")), result.getDetails().get(DetectionDetails.Type.TOR));
    }
    @ParameterizedTest @ValueSource(strings={"missing-vpn", "null-proxy", "string-tor", "missing-success", "false-proxy", "wrong-ip", "risk-text", "risk-negative", "risk-large", "risk-exponent", "asn-text", "asn-zero", "asn-fraction", "country-invalid", "isp-object"})
    void malformedSourceCannotBecomeANegative(String mutation) throws Exception {
        JsonObject value = json(body);
        switch (mutation) {
            case "missing-vpn": value.remove("vpn"); break;
            case "null-proxy": value.add("proxy", JsonNull.INSTANCE); break;
            case "string-tor": value.addProperty("tor", "false"); break;
            case "missing-success": value.remove("success"); break;
            case "false-proxy": value.addProperty("vpn", true); break;
            case "wrong-ip": value.addProperty("ip", "192.0.2.99"); break;
            case "risk-text": value.addProperty("fraud_score", "79.9"); break;
            case "risk-negative": value.addProperty("fraud_score", -1); break;
            case "risk-large": value.addProperty("fraud_score", 100.1); break;
            case "risk-exponent": value.add("fraud_score", JsonParser.parseString("1e-1001")); break;
            case "asn-text": value.addProperty("ASN", "64500"); break;
            case "asn-zero": value.addProperty("ASN", 0); break;
            case "asn-fraction": value.addProperty("ASN", 64500.5); break;
            case "country-invalid": value.addProperty("country_code", "Portugal"); break;
            case "isp-object": value.add("ISP", new JsonObject()); break;
            default: fail(mutation);
        }
        body = value.toString();
        ExecutionException error = assertThrows(ExecutionException.class, () -> query("192.0.2.1"));
        assertEquals(FailureReason.INVALID_RESPONSE, LookupException.reason(error)); assertFalse(error.getCause().getMessage().contains("synthetic-header-key"));
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,503,302})
    void actualHttpFailuresAndRedirectsNeverFollowOrVote(int code) {
        status = code; ExecutionException error = assertThrows(ExecutionException.class, () -> query("192.0.2.1"));
        assertEquals(code == 429 ? FailureReason.RATE_LIMIT : code == 401 || code == 403 ? FailureReason.AUTHENTICATION : FailureReason.HTTP_ERROR, LookupException.reason(error));
        assertEquals(1, calls.get(), "Redirect must not forward the header.");
    }
    @Test void creditExhaustionIsUnknownAndPausesFurtherLookupsWithoutLoweringQuorum() throws Exception {
        body = "{\"success\":false,\"message\":\"You have insufficient credits for this synthetic request.\",\"proxy\":false,\"vpn\":false,\"tor\":false}";
        select(); VpnResult result = ConnectionGuard.getVpnResult("192.0.2.1").get(3, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.UNKNOWN, result.getStatus()); assertEquals(FailureReason.RATE_LIMIT, result.getVotes().get(0).getReason());
        VpnResult next = ConnectionGuard.getVpnResult("192.0.2.2").get(3, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.UNKNOWN, next.getStatus()); assertEquals(FailureReason.CIRCUIT_OPEN, next.getVotes().get(0).getReason());
        assertEquals(1, calls.get()); assertFalse(ConnectionGuard.providerHealth().values().iterator().next().describe().contains("synthetic-header-key"));
    }
    @Test void unknownFailureBodyAndInvalidLiteralNeverCreateFacts() throws Exception {
        body = "{\"success\":false,\"message\":\"Synthetic unknown error\"}";
        assertEquals(FailureReason.INVALID_RESPONSE, LookupException.reason(assertThrows(ExecutionException.class, () -> query("192.0.2.1"))));
        assertEquals(FailureReason.INVALID_RESPONSE, LookupException.reason(assertThrows(ExecutionException.class, () -> query("example.invalid"))));
        assertEquals(1, calls.get());
    }
    @Test void configuredLocalBudgetStopsAnotherUniqueAddressBeforeHttp() throws Exception {
        select(); ConnectionGuard.setProviderBudget("IpQualityScoreVpnProvider#0", 1, 0);
        assertEquals(ProviderVote.Status.NEGATIVE, ConnectionGuard.getVpnResult("192.0.2.1").get(3, TimeUnit.SECONDS).getStatus());
        VpnResult result = ConnectionGuard.getVpnResult("192.0.2.2").get(3, TimeUnit.SECONDS);
        assertEquals(FailureReason.BUDGET_EXHAUSTED, result.getVotes().get(0).getReason()); assertEquals(1, calls.get());
    }
    @Test void oneHundredSameIpQueriesShareOneNativeHttpAndPreserveFractionalSourceRisk() throws Exception {
        release = new CountDownLatch(1); select(); List<CompletableFuture<VpnResult>> pending = new ArrayList<>();
        for (int i = 0; i < 100; i++) pending.add(ConnectionGuard.getVpnResult("192.0.2.1"));
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(1, calls.get()); release.countDown();
        for (CompletableFuture<VpnResult> future : pending) {
            VpnResult result = future.get(3, TimeUnit.SECONDS); assertEquals(ProviderVote.Status.NEGATIVE, result.getStatus());
            assertEquals(new BigDecimal("79.999"), result.getVotes().get(0).getDetails().getExactRisk());
        }
        assertEquals(1, calls.get());
    }
    @Test void boundedTransportRejectsOversizeInvalidJsonAndSlowResponses() throws Exception {
        body = String.join("", Collections.nCopies(262145, "x"));
        assertEquals(FailureReason.INVALID_RESPONSE, LookupException.reason(assertThrows(ExecutionException.class, () -> query("192.0.2.1"))));
        body = "synthetic invalid JSON";
        assertEquals(FailureReason.INVALID_RESPONSE, LookupException.reason(assertThrows(ExecutionException.class, () -> query("192.0.2.1"))));
        delay = 400; ProviderHttp.configure(new LookupSettings(1000, 100, 8, 64, 128, 3, 30000));
        assertEquals(FailureReason.TIMEOUT, LookupException.reason(assertThrows(ExecutionException.class, () -> query("192.0.2.1"))));
    }
}
