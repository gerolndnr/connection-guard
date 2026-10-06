package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class ProxyCheckRecordedAnswersTest {
    static Stream<JsonObject> recorded() throws IOException {
        try (Reader reader = new InputStreamReader(ProxyCheckRecordedAnswersTest.class.getResourceAsStream(
                "/providers/proxycheck-recorded-v2-v3.json"), StandardCharsets.UTF_8)) {
            return StreamSupport.stream(JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases").spliterator(), false)
                    .map(JsonElement::getAsJsonObject).collect(Collectors.toList()).stream();
        }
    }
    @ParameterizedTest @MethodSource("recorded")
    void recordedVpnExitsArePositiveAndUnflaggedPrivateBusinessAndWirelessAnswersAreNegative(JsonObject fixture) {
        String ip = fixture.get("ip").getAsString(); boolean expected = fixture.get("expected_v2_vpn").getAsBoolean();
        VpnResult result = ProxyCheckVpnProvider.parse(ip, fixture.getAsJsonObject("responses").getAsJsonObject("v2")).get();
        assertEquals(expected, result.isVpn(), fixture.get("id").getAsString());
        if (expected) assertEquals(Boolean.TRUE, result.getDetails().get(DetectionDetails.Type.VPN));
        if (fixture.getAsJsonObject("responses").has("v3")) {
            VpnResult oldSignal = ProxyCheckVpnProvider.parseV3(ip, fixture.getAsJsonObject("responses").getAsJsonObject("v3")).get();
            assertFalse(oldSignal.isVpn(), "The recorded v3 review/negative must never be promoted by a brand name.");
        }
    }
    @Test void recordedSetContainsAllEighteenMissesAndBusinessResidentialWirelessControls() throws Exception {
        java.util.List<JsonObject> cases = recorded().collect(Collectors.toList());
        assertEquals(18, cases.stream().filter(f -> f.get("expected_v2_vpn").getAsBoolean()).count());
        assertEquals(9, cases.stream().filter(f -> f.get("cohort").getAsString().equals("vpn_v6")).count());
        for (String label : new String[]{"Business", "Residential", "Wireless"})
            assertTrue(cases.stream().filter(f -> !f.get("expected_v2_vpn").getAsBoolean()).anyMatch(f -> {
                JsonObject response = f.getAsJsonObject("responses").getAsJsonObject("v2").getAsJsonObject(f.get("ip").getAsString());
                return response.has("type") && label.equals(response.get("type").getAsString());
            }), label);
    }
    @Test void hostingOnlyIsReviewEvenWhenTheBroadV2ProxyFlagIsYes() {
        VpnResult result = ProxyCheckVpnProvider.parse("192.0.2.1", JsonParser.parseString(
                "{\"status\":\"ok\",\"192.0.2.1\":{\"proxy\":\"yes\",\"type\":\"Hosting\"}}").getAsJsonObject()).get();
        assertFalse(result.isVpn()); assertEquals(Boolean.TRUE, result.getDetails().get(DetectionDetails.Type.HOSTING));
    }
}
