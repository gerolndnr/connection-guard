package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.vpn.IpQualityScoreVpnProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IpQualityScoreConfigurationTest {
    private Map<String,Object> values() {
        Map<String,Object> value = new HashMap<>(); value.put("provider.geo.service", "Disabled");
        value.put("provider.vpn.ipqualityscore.enabled", true); value.put("provider.vpn.ipqualityscore.api-key", "synthetic-config-key");
        return value;
    }
    private ProviderConfiguration draft(Map<String,Object> value) { return new ProviderConfiguration(value::get, Collections.singletonList("ipqualityscore")); }
    @Test void optionalDisabledProviderDoesNotRequireKeyAndNativeSelectionHasFiniteDefaults() {
        Map<String,Object> value = values(); value.put("provider.vpn.ipqualityscore.enabled", false); value.remove("provider.vpn.ipqualityscore.api-key");
        assertTrue(draft(value).providers.isEmpty()); value = values(); ProviderConfiguration configured = draft(value);
        assertEquals(1, configured.providers.size()); assertInstanceOf(IpQualityScoreVpnProvider.class, configured.providers.get(0));
        assertEquals(Integer.valueOf(30), configured.dayBudgets.get("IpQualityScoreVpnProvider#0"));
        assertEquals(Integer.valueOf(5), configured.minuteBudgets.get("IpQualityScoreVpnProvider#0"));
        assertTrue(configured.settings.warnings.stream().anyMatch(w -> w.contains("not the account monthly balance")));
    }
    @Test void changedKeyOrRequestOptionsCannotReusePreviousSourceCacheNamespace() {
        Map<String,Object> value = values(); String original = draft(value).cacheNamespace;
        for (Object[] change : new Object[][]{{"api-key", "synthetic-changed-key"}, {"strictness", 1}, {"fast", false}, {"allow-public-access-points", false}}) {
            Map<String,Object> changed = values(); changed.put("provider.vpn.ipqualityscore." + change[0], change[1]);
            assertNotEquals(original, draft(changed).cacheNamespace);
        }
        assertFalse(original.contains("synthetic-config-key"));
    }
    @Test void invalidKeyOptionsAndBudgetsRejectCompleteDraftWithRedactedErrors() {
        for (Object[] change : new Object[][]{{"api-key", ""}, {"api-key", "synthetic-private\nkey"}, {"strictness", -1}, {"strictness", 4}, {"strictness", 1.5}, {"fast", "synthetic-private"}, {"daily-budget", -1}, {"minute-budget", -1}}) {
            Map<String,Object> changed = values(); changed.put("provider.vpn.ipqualityscore." + change[0], change[1]);
            Exception error = assertThrows(IllegalArgumentException.class, () -> draft(changed)); assertFalse(error.getMessage().contains("synthetic-private"));
        }
        Map<String,Object> changed = values(); changed.put("provider.vpn.ipqualityscore.strictness", 2);
        assertTrue(draft(changed).settings.warnings.stream().anyMatch(w -> w.contains("false-positive risk")));
    }
}
