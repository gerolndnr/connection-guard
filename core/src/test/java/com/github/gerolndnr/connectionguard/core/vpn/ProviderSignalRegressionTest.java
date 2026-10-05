package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderAddresses;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderSignalRegressionTest {
    private JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private VpnResult v3(String operator) {
        return ProxyCheckVpnProvider.parseV3("2001:db8:0:0:0:0:0:7", json("{\"status\":\"ok\",\"2001:db8::7\":{\"detections\":{\"anonymous\":false,\"hosting\":true,\"vpn\":false,\"proxy\":false,\"tor\":false},\"operator\":" + operator + "}}")).get();
    }
    @Test void knownVpnOperatorAndVpnServiceBlockHostingExits() {
        assertTrue(v3("{\"name\":\"IVPN\"}").isVpn());
        assertTrue(v3("{\"name\":\"New operator\",\"services\":[\"datacenter_vpns\"]}").isVpn());
        assertEquals(Boolean.FALSE, v3("{\"name\":\"Mullvad\"}").getDetails().get(DetectionDetails.Type.VPN));
    }
    @Test void hostingOnlyAndScraperOperatorRemainReviewEvidence() {
        assertFalse(v3("null").isVpn());
        assertFalse(v3("{\"name\":\"Example scraper\",\"services\":[\"scraping\"]}").isVpn());
        assertEquals(Boolean.TRUE, v3("null").getDetails().get(DetectionDetails.Type.HOSTING));
    }
    @Test void compressedExpandedAndUpperCaseIpv6ShareResponseIdentity() {
        assertEquals("2001:db8::7", ProviderAddresses.compressed("2001:0DB8:0:0:0:0:0:7"));
        assertEquals("::", ProviderAddresses.compressed("0:0:0:0:0:0:0:0"));
        assertEquals("2001:db8:0:1::7", ProviderAddresses.compressed("2001:db8:0:1:0:0:0:7"));
        assertEquals("2001:db8::1:0:0:1", ProviderAddresses.compressed("2001:db8:0:0:1:0:0:1"));
        assertThrows(IllegalArgumentException.class, () -> ProviderAddresses.response(json("{\"2001:db8::8\":{}}"), "2001:db8::7"));
        assertThrows(IllegalArgumentException.class, () -> ProviderAddresses.response(json("{\"2001:db8::7\":{},\"2001:db8:0:0:0:0:0:7\":{}}"), "2001:db8::7"));
    }
    @Test void ipQueryRequiresAllThreeSecurityFlagsAndDoesNotGuessHosting() {
        assertFalse(IpQueryVpnProvider.parse("192.0.2.7", json("{\"ip\":\"192.0.2.7\",\"risk\":{\"is_vpn\":false,\"is_proxy\":false}}" )).isPresent());
        assertTrue(IpQueryVpnProvider.parse("192.0.2.7", json("{\"ip\":\"192.0.2.7\",\"risk\":{\"is_vpn\":false,\"is_proxy\":false,\"is_tor\":true}}" )).get().isVpn());
        assertFalse(IpQueryVpnProvider.parse("192.0.2.7", json("{\"ip\":\"192.0.2.7\",\"risk\":{\"is_vpn\":false,\"is_proxy\":false,\"is_tor\":false,\"is_datacenter\":true}}" )).get().isVpn());
    }
}
