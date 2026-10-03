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
    @Test void nativeIdentityOptInIsStrictAndMissingSdkCannotPretendAvailability() {
        assertFalse(GuardSettings.defaults().nativeFloodgateIdentity);
        Map<String,Object> fields=new HashMap<>();fields.put("identity.floodgate.enabled",true);
        GuardSettings settings=GuardSettings.read(fields::get,Collections.emptyList());assertTrue(settings.nativeFloodgateIdentity);
        assertTrue(settings.warnings.stream().anyMatch(s->s.contains("canonical API is unavailable")));
        fields.put("identity.floodgate.enabled","synthetic-private-invalid");
        Exception error=assertThrows(IllegalArgumentException.class,()->GuardSettings.read(fields::get,Collections.emptyList()));
        assertFalse(error.getMessage().contains("synthetic-private-invalid"));
    }
    @Test void invalidWebhookIsRedacted() {
        Map<String, Object> fields = new HashMap<>(); fields.put("behavior.vpn.send-webhook.enabled", true);
        fields.put("behavior.vpn.send-webhook.url", "http://example.invalid/fake-sensitive-secret");
        Exception failure = assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(fields::get, Collections.emptyList()));
        assertFalse(failure.getMessage().contains("fake-sensitive-secret"));
    }
    @Test void nativePaperForwardingRequiresAnExplicitBooleanChoice() {
        assertFalse(GuardSettings.defaults().nativePaperForwardingIdentity);
        Map<String,Object> fields=new HashMap<>();fields.put("identity.paper-modern-forwarding.enabled",true);
        assertTrue(GuardSettings.read(fields::get,Collections.emptyList()).nativePaperForwardingIdentity);
        fields.put("identity.paper-modern-forwarding.enabled","synthetic-private-invalid");
        Exception invalid=assertThrows(IllegalArgumentException.class,()->GuardSettings.read(fields::get,Collections.emptyList()));
        assertFalse(invalid.getMessage().contains("synthetic-private-invalid"));
    }
}
