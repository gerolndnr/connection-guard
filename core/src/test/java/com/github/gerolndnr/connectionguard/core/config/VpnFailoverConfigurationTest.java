package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VpnFailoverConfigurationTest {
    @TempDir Path directory;
    private Map<String, Object> keyed() {
        Map<String, Object> values = new HashMap<>();
        values.put("provider.vpn.iphub.enabled", true);
        values.put("provider.vpn.iphub.api-key", "synthetic-key");
        values.put("provider.vpn.vpnapi.enabled", true);
        values.put("provider.vpn.vpnapi.api-key", "synthetic-key");
        values.put("required-positive-flags", 2);
        values.put("operation.mode", "OBSERVE");
        values.put("provider.geo.service", "Disabled");
        return values;
    }
    private ProviderConfiguration draft(Map<String, Object> values, String... keys) {
        return new ProviderConfiguration(values::get, Arrays.asList(keys), directory);
    }
    @Test void missingOptionEnablesFailoverWithoutProxyCheckOrFreeProviders() {
        ProviderConfiguration result = draft(keyed(), "vpnapi", "iphub");
        assertTrue(result.failover); assertEquals(1, result.threshold); assertEquals(16, result.externalAttempts);
        assertTrue(result.settings.observe); assertEquals(Arrays.asList("vpnapi", "iphub"), result.keys);
        assertTrue(result.settings.warnings.stream().anyMatch(w -> w.contains("effective threshold 1")));
    }
    @Test void disablingRestoresOriginalOrderAndStoredConsensusThreshold() {
        Map<String, Object> values = keyed(); values.put("provider.vpn-failover.enabled", false);
        values.put("provider.vpn-failover.order", Arrays.asList("iphub", "vpnapi"));
        ProviderConfiguration result = draft(values, "vpnapi", "iphub");
        assertFalse(result.failover); assertEquals(2, result.threshold);
        assertEquals(Arrays.asList("vpnapi", "iphub"), result.keys); assertTrue(result.settings.observe);
    }
    @Test void explicitLegacyStrategyIsRetainedAndNewSwitchTakesPrecedence() {
        Map<String, Object> values = keyed(); values.put("provider.vpn-strategy", "CONSENSUS");
        assertFalse(draft(values, "iphub", "vpnapi").failover);
        values.put("provider.vpn-failover.enabled", true);
        assertTrue(draft(values, "iphub", "vpnapi").failover);
        values.put("provider.vpn-strategy", "FAILOVER"); values.put("provider.vpn-failover.enabled", false);
        assertEquals(2, draft(values, "iphub", "vpnapi").threshold);
    }
    @Test void orderCanPreferKeyedCustomAndExtensionSourcesWhileIpApiStaysLast() {
        Map<String, Object> values = keyed(); values.put("required-positive-flags", 1);
        values.put("provider.vpn.proxycheck.enabled", true); values.put("provider.vpn.proxycheck.api-key", "synthetic-key");
        values.put("provider.vpn.ip-api.enabled", true);
        values.put("provider.vpn.corporate.enabled", true);
        values.put("provider.vpn.corporate.request-url", "https://example.invalid/%IP%");
        values.put("provider.vpn.corporate.response-format.is-vpn-field.field-name", "vpn");
        values.put("integrations.providers.enabled", true);
        Map<String, Object> source = new HashMap<>(); source.put("id", "owned"); source.put("voting", true);
        values.put("integrations.providers.sources", Collections.singletonList(source));
        values.put("provider.vpn-failover.order", Arrays.asList("ip-api", "extension.owned", "corporate", "iphub"));
        values.put("provider.vpn.iphub.daily-budget", 7);
        values.put("provider.vpn.corporate.daily-budget", 11);
        ProviderConfiguration result = draft(values, "proxycheck", "ip-api", "vpnapi", "corporate", "iphub");
        assertEquals(Arrays.asList("extension.owned", "corporate", "iphub", "proxycheck", "vpnapi", "ip-api"), result.keys);
        assertEquals(Integer.valueOf(7), result.dayBudgets.get(ConnectionGuard.providerId(result.providers.get(2), 2)));
        assertEquals(Integer.valueOf(11), result.dayBudgets.get(ConnectionGuard.providerId(result.providers.get(1), 1)));
        assertEquals("vpn.iphub", result.healthIds.get(ConnectionGuard.providerId(result.providers.get(2), 2)));
    }
    @Test void localObservationsPrecedeAnExplicitNetworkOrder() {
        Map<String, Object> values = keyed(); values.put("required-positive-flags", 1);
        values.put("provider.vpn.local.enabled", true);
        Map<String, Object> source = new HashMap<>(); source.put("id", "tor"); source.put("type", "TOR");
        source.put("source", "Synthetic fixture"); source.put("license", "MIT"); source.put("notice", "Fixture");
        values.put("provider.local.sources", Collections.singletonList(source));
        values.put("provider.vpn-failover.order", Arrays.asList("iphub", "vpnapi"));
        assertEquals(Arrays.asList("local.tor", "iphub", "vpnapi"), draft(values, "vpnapi", "iphub", "local").keys);
    }
    @Test void strategyAndOrderChangesHaveDifferentCacheNamespaces() {
        Map<String, Object> values = keyed(); ProviderConfiguration original = draft(values, "iphub", "vpnapi");
        values.put("provider.vpn-failover.order", Arrays.asList("vpnapi", "iphub"));
        assertNotEquals(original.cacheNamespace, draft(values, "iphub", "vpnapi").cacheNamespace);
        values.put("provider.vpn-failover.enabled", false);
        assertNotEquals(original.cacheNamespace, draft(values, "iphub", "vpnapi").cacheNamespace);
    }
    @Test void invalidSwitchOrderAndBudgetAreRejectedWithoutExposingValues() {
        for (Object bad : Arrays.asList("synthetic-private-invalid", 1)) {
            Map<String, Object> values = keyed(); values.put("provider.vpn-failover.enabled", bad);
            Exception error = assertThrows(IllegalArgumentException.class, () -> draft(values, "iphub", "vpnapi"));
            assertFalse(error.getMessage().contains("synthetic-private-invalid"));
        }
        for (Object bad : Arrays.asList("synthetic-private-invalid", Arrays.asList("iphub", "iphub"),
                Collections.singletonList("synthetic-private-invalid"), Collections.singletonList(42), Collections.nCopies(17, "iphub"))) {
            Map<String, Object> values = keyed(); values.put("provider.vpn-failover.order", bad);
            Exception error = assertThrows(IllegalArgumentException.class, () -> draft(values, "iphub", "vpnapi"));
            assertFalse(error.getMessage().contains("synthetic-private-invalid"));
        }
        for (int limit : Arrays.asList(0, 17)) {
            Map<String, Object> values = keyed(); values.put("provider.max-external-attempts", limit);
            assertThrows(IllegalArgumentException.class, () -> draft(values, "iphub", "vpnapi"));
        }
    }
}
