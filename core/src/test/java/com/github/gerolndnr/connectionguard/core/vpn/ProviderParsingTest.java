package com.github.gerolndnr.connectionguard.core.vpn;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderParsingTest {
    private JsonObject json(String body) { return JsonParser.parseString(body).getAsJsonObject(); }
    @Test void proxyCheckRejectsUnknownStatusesAndFlags() {
        assertFalse(ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"denied\"}")).isPresent());
        assertFalse(ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"unexpected\"}")).isPresent());
        assertFalse(ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"ok\",\"192.0.2.1\":{\"proxy\":\"unknown\"}}")).isPresent());
    }
    @Test void proxyCheckAcceptsWarningWithAValidVerdict() {
        assertTrue(ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"warning\",\"192.0.2.1\":{\"proxy\":\"yes\"}}")).get().isVpn());
    }
    @Test void ipApiFailureIsUnavailableAndMissingFlagIsNotFalse() {
        assertFalse(IpApiVpnProvider.parse("192.0.2.1", json("{\"status\":\"fail\"}")).isPresent());
        assertThrows(IllegalArgumentException.class, () -> IpApiVpnProvider.parse("192.0.2.1", json("{\"status\":\"success\"}")));
        assertFalse(IpApiVpnProvider.parse("192.0.2.1", json("{\"status\":\"success\",\"proxy\":false}")).get().isVpn());
    }
    @Test void ipHubPreservesBlockLevelsAndRejectsInvalidValues() {
        assertFalse(IpHubVpnProvider.parse("192.0.2.1", json("{\"block\":0}")).get().isVpn());
        assertTrue(IpHubVpnProvider.parse("192.0.2.1", json("{\"block\":1}")).get().isVpn());
        assertFalse(IpHubVpnProvider.parse("192.0.2.1", json("{\"block\":2}")).get().isVpn());
        assertFalse(IpHubVpnProvider.parse("192.0.2.1", json("{\"block\":0.5}")).isPresent());
    }
    @Test void vpnApiRequiresAllFourFlags() {
        assertTrue(VpnApiVpnProvider.parse("192.0.2.1", json("{\"security\":{\"vpn\":false,\"proxy\":false,\"tor\":true,\"relay\":false}}")).get().isVpn());
        assertThrows(IllegalArgumentException.class, () -> VpnApiVpnProvider.parse("192.0.2.1", json("{\"security\":{\"vpn\":false,\"proxy\":false,\"tor\":false}}")));
    }
    @Test void vpnApiKeepsDistinctFalseFlagsAndActualAsnWithMissingRiskUnknown() {
        VpnResult result = VpnApiVpnProvider.parse("2001:db8::1", json("{\"ip\":\"2001:db8::1\",\"security\":{\"vpn\":false,\"proxy\":false,\"tor\":true,\"relay\":false},\"network\":{\"autonomous_system_number\":\"AS15169\",\"autonomous_system_organization\":\"GOOGLE\"},\"location\":{\"country_code\":\"US\"}}")).get();
        assertEquals(Boolean.FALSE, result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.VPN));
        assertEquals(Boolean.TRUE, result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.TOR));
        assertNull(result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.HOSTING));
        assertEquals(Long.valueOf(15169), result.getDetails().getAsn()); assertNull(result.getDetails().getRisk());
    }
    @Test void ipApiDoesNotPretendItsCombinedFlagIsSeparateProxyOrVpnEvidence() {
        VpnResult result = IpApiVpnProvider.parse("192.0.2.1", json("{\"status\":\"success\",\"proxy\":true,\"hosting\":false,\"as\":\"AS15169 Google Inc.\",\"countryCode\":\"US\"}")).get();
        assertTrue(result.isVpn()); assertNull(result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.PROXY));
        assertEquals(Boolean.FALSE, result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.HOSTING));
        assertEquals(Long.valueOf(15169), result.getDetails().getAsn());
    }
    @Test void ipHubHostingIsNotRelabelledAsVpnAndV2MetadataCanBeAbsent() {
        VpnResult result = IpHubVpnProvider.parse("192.0.2.1", json("{\"block\":1,\"asn\":16509,\"proxyType\":{\"proxy\":false,\"tor\":false,\"relay\":false,\"hosting\":true}}")).get();
        assertNull(result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.VPN));
        assertEquals(Boolean.TRUE, result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.HOSTING));
        assertNull(IpHubVpnProvider.parse("192.0.2.1", json("{\"block\":1}")).get().getDetails().getAsn());
    }
    @Test void proxyCheckV2SingleTypeDoesNotInventOtherNegativesOrRisk() {
        VpnResult result = ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"ok\",\"192.0.2.1\":{\"proxy\":\"yes\",\"type\":\"TOR\",\"asn\":\"AS15169\",\"risk\":80}}")).get();
        assertEquals(Boolean.TRUE, result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.TOR));
        assertNull(result.getDetails().get(com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.VPN));
        assertEquals(Integer.valueOf(80), result.getDetails().getRisk());
        assertNull(ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"ok\",\"192.0.2.1\":{\"proxy\":\"no\",\"type\":\"NewFutureType\"}}")).get().getDetails().getRisk());
    }
    @Test void proxyCheckV3UsesTopLevelRiskAndNestedConfidenceAndOperator() {
        VpnResult result = ProxyCheckVpnProvider.parseV3("192.0.2.1", json("{\"status\":\"ok\",\"192.0.2.1\":{\"risk\":50,\"detections\":{\"anonymous\":true,\"vpn\":true,\"proxy\":false,\"tor\":false,\"hosting\":true,\"confidence\":95},\"network\":{\"asn\":\"AS209854\",\"provider\":\"Fixture ISP\"},\"location\":{\"isocode\":\"DE\"},\"operator\":{\"name\":\"IVPN\"}}}")).get();
        assertEquals(Integer.valueOf(50), result.getDetails().getRisk()); assertEquals(Integer.valueOf(95), result.getDetails().getConfidence());
        assertEquals("IVPN", result.getDetails().getOperator()); assertEquals("DE", result.getDetails().getCountry());
    }
    @Test void malformedOptionalFieldsAndWrongAddressesInvalidateTheResponse() {
        assertThrows(IllegalArgumentException.class, () -> ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"ok\",\"192.0.2.1\":{\"proxy\":\"no\",\"risk\":\"0\"}}")));
        assertThrows(ArithmeticException.class, () -> ProxyCheckVpnProvider.parse("192.0.2.1", json("{\"status\":\"ok\",\"192.0.2.1\":{\"proxy\":\"no\",\"risk\":0.5}}")));
        assertThrows(IllegalArgumentException.class, () -> IpHubVpnProvider.parse("192.0.2.1", json("{\"ip\":\"192.0.2.2\",\"block\":0}")));
    }
}
