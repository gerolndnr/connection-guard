package com.github.gerolndnr.connectionguard.core.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GuardSettingsTest {
    @Test void missingNewFieldsPreserveLegacyEnforcement() { assertFalse(GuardSettings.defaults().observe); }
    @Test void explicitObserveIsRespected() {
        assertTrue(GuardSettings.read(path -> path.equals("operation.mode") ? "OBSERVE" : null, Collections.emptyList()).observe);
    }
    @Test void invalidThresholdAndTimeoutAreRejectedWithoutLeakingValues() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("provider.vpn.proxycheck.enabled", true); fields.put("required-positive-flags", 2);
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(fields::get, Collections.singletonList("proxycheck")));
        fields.put("required-positive-flags", 1); fields.put("lookup.http-timeout-ms", 6000);
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(fields::get, Collections.singletonList("proxycheck")));
    }
    @Test void invalidWebhookIsRedacted() {
        Map<String, Object> fields = new HashMap<>(); fields.put("behavior.vpn.send-webhook.enabled", true);
        fields.put("behavior.vpn.send-webhook.url", "http://example.invalid/fake-sensitive-secret");
        Exception failure = assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(fields::get, Collections.emptyList()));
        assertFalse(failure.getMessage().contains("fake-sensitive-secret"));
    }
}
