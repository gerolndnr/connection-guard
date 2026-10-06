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
    @Test void operatorNamesAloneNeverSupplyVpnEvidence() {
        for (String name : java.util.Arrays.asList("IVPN", "Mullvad", "NordVPN", "Surfshark", "PIA", "Private Internet Access", "Proton", "ProtonVPN", "Proton VPN", "Express", "ExpressVPN", "Windscribe", "Unknown company")) {
            VpnResult result = v3("{\"name\":\"" + name + "\"}");
            assertFalse(result.isVpn(), name + " has no explicit flags or VPN services");
            assertEquals(Boolean.FALSE, result.getDetails().get(DetectionDetails.Type.VPN));
            assertEquals(Boolean.TRUE, result.getDetails().get(DetectionDetails.Type.HOSTING));
        }
    }
    @Test void genericVpnServicesWorkForEveryOperatorName() {
        for (String category : java.util.Arrays.asList("datacenter_vpns", "residential_vpns", "mobile_vpns")) {
            assertTrue(v3("{\"name\":\"Previously unseen company\",\"services\":[\"" + category + "\"]}").isVpn());
            assertTrue(v3("{\"services\":[\"" + category + "\"]}").isVpn());
        }
        assertFalse(v3("{\"name\":\"Mullvad\",\"services\":[\"hosting\"]}").isVpn());
        assertFalse(v3("{\"services\":[\"not_vpn\"]}").isVpn());
        assertThrows(IllegalArgumentException.class, () -> v3("{\"services\":true}"));
        assertThrows(IllegalArgumentException.class, () -> v3("{\"services\":[true]}"));
    }
    @Test void explicitV3SignalsDoNotRequireAnOperatorName() {
        for (String signal : java.util.Arrays.asList("vpn", "proxy", "tor", "anonymous")) {
            String body = "{\"status\":\"ok\",\"192.0.2.7\":{\"detections\":{\"" + signal + "\":true}}}";
            assertTrue(ProxyCheckVpnProvider.parseV3("192.0.2.7", json(body)).get().isVpn());
        }
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
