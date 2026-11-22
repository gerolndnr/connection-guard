package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic security-mode bodies and sanitized recorded responses; no public lookups. */
class ZowiSecurityModeTest {
    private static final String IP = "203.0.113.9";

    private static JsonObject securityOnly(boolean vpn, boolean proxy, boolean tor, boolean hosting) {
        return JsonParser.parseString("{\"ip\":\"" + IP + "\",\"malicious\":true,\"security\":{"
                + "\"bogon\":false,\"vpn\":{\"detected\":" + vpn + "},\"proxy\":{\"detected\":" + proxy + "},"
                + "\"hosting\":" + hosting + ",\"residential_proxy\":{\"detected\":false},\"tor\":" + tor
                + ",\"abuser\":{\"detected\":false},\"crawler\":{\"detected\":false},\"suspicious\":false},"
                + "\"rateLimit\":{\"limit\":150,\"remaining\":149}}").getAsJsonObject();
    }

    @ParameterizedTest
    @CsvSource({"203.0.113.9,203.0.113.9", "2001:0DB8:0:0:0:0:0:7,2001:db8::7"})
    void productionRequestUsesOnlySecurityModeAndTheCompressedAddress(String ip, String compressed) {
        Request request = new ZowiVpnProvider().request(ip);
        assertEquals("https://api.zowi.gay/" + compressed + "?feature=security", request.url().toString());
        assertEquals(Collections.singleton("feature"), request.url().queryParameterNames());
        assertEquals("GET", request.method());
        assertEquals(0, request.headers().size());
        assertNull(request.body());
    }

    @ParameterizedTest
    @CsvSource({"true,false,false,true,POSITIVE", "false,true,false,false,POSITIVE",
            "false,false,true,false,POSITIVE", "false,false,false,false,NEGATIVE", "false,false,false,true,UNKNOWN"})
    void securityOnlyBodiesPreserveExplicitSignalsAndHostingReview(boolean vpn, boolean proxy, boolean tor,
                                                                  boolean hosting, String expected) {
        VpnResult result = ZowiVpnProvider.parse(IP, securityOnly(vpn, proxy, tor, hosting)).get();
        assertEquals(ProviderVote.Status.valueOf(expected), result.getStatus());
        assertEquals(vpn || proxy || tor, result.isVpn());
        assertEquals(Boolean.valueOf(vpn), result.getDetails().get(DetectionDetails.Type.VPN));
        assertEquals(Boolean.valueOf(proxy), result.getDetails().get(DetectionDetails.Type.PROXY));
        assertEquals(Boolean.valueOf(tor), result.getDetails().get(DetectionDetails.Type.TOR));
        assertEquals(Boolean.valueOf(hosting), result.getDetails().get(DetectionDetails.Type.HOSTING));
        assertNull(result.getDetails().getAsn());
        assertNull(result.getDetails().getCountry());
        if (expected.equals("UNKNOWN")) assertEquals(FailureReason.NO_EVIDENCE, result.getSourceReason());
    }

    @Test
    void missingSecuritySignalsNeverBecomeCleanFromOtherTopLevelFields() {
        JsonObject json = securityOnly(false, false, false, false);
        json.getAsJsonObject("security").remove("tor");
        assertFalse(ZowiVpnProvider.parse(IP, json).isPresent());
    }

    @Test
    void malformedSecuritySignalsStillInvalidateTheAnswer() {
        JsonObject json = securityOnly(false, false, false, false);
        json.getAsJsonObject("security").getAsJsonObject("proxy").addProperty("detected", "false");
        assertThrows(IllegalArgumentException.class, () -> ZowiVpnProvider.parse(IP, json));
    }

    @Test
    void removingUnusedFieldsFromRecordedAnswersPreservesClassifications() throws Exception {
        int compared = 0;
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/providers/keyless-recorded.json"), StandardCharsets.UTF_8)) {
            for (JsonElement item : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases")) {
                JsonObject fixture = item.getAsJsonObject();
                JsonObject responses = fixture.getAsJsonObject("responses");
                if (!responses.has("zowi")) continue;
                String ip = fixture.get("ip").getAsString();
                JsonObject full = responses.getAsJsonObject("zowi"), reduced = new JsonObject();
                if (full.has("ip")) reduced.add("ip", full.get("ip").deepCopy());
                reduced.add("security", full.get("security").deepCopy());
                Optional<VpnResult> before = ZowiVpnProvider.parse(ip, full), after = ZowiVpnProvider.parse(ip, reduced);
                assertEquals(before.isPresent(), after.isPresent());
                if (before.isPresent()) {
                    assertEquals(before.get().getStatus(), after.get().getStatus());
                    assertEquals(before.get().getSourceReason(), after.get().getSourceReason());
                    assertEquals(before.get().getDetails().getClassifications(), after.get().getDetails().getClassifications());
                }
                compared++;
            }
        }
        assertTrue(compared > 0, "Recorded zowi cases must be exercised.");
    }

    @Test
    void actualLoopbackTransportUsesTheSameSecurityParameter() throws Exception {
        AtomicReference<URI> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/zowi", exchange -> {
            captured.set(exchange.getRequestURI());
            try {
                byte[] bytes = securityOnly(true, false, false, true).toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ZowiVpnProvider provider = new ZowiVpnProvider(HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/zowi"));
            VpnResult result = provider.getVpnResult(IP).get(3, TimeUnit.SECONDS).get();
            assertTrue(result.isVpn());
            assertEquals("/zowi/" + IP, captured.get().getPath());
            assertEquals("feature=security", captured.get().getRawQuery());
        } finally { server.stop(0); }
    }
}
