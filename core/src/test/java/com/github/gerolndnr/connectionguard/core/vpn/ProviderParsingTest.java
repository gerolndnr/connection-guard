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
}
