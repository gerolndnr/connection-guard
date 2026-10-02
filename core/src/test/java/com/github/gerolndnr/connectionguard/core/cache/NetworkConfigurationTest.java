package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkConfigurationTest {
    private Map<String, Object> redis() {
        Map<String, Object> values = new HashMap<>();
        values.put("provider.cache.type", "Redis"); values.put("provider.cache.redis.hostname", "127.0.0.1");
        values.put("provider.geo.service", "Disabled"); return values;
    }
    @Test void invalidNetworkDraftsAreRedactedAndRejectedBeforeActivation() {
        Map<String, Object> values = redis();
        for (int port : new int[]{0, -1, 65536}) {
            values.put("provider.cache.redis.port", port);
            assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(values::get, Collections.emptyList()));
        }
        values.put("provider.cache.redis.port", 6379);
        for (String host : new String[]{"", "https://private.invalid", "user:password@private.invalid", "host\\nprivate", "host with spaces"}) {
            values.put("provider.cache.redis.hostname", host);
            IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(values::get, Collections.emptyList()));
            if (!host.isEmpty()) assertFalse(invalid.getMessage().contains(host));
        }
        values.put("provider.cache.redis.hostname", "::1");
        assertNotNull(GuardSettings.read(values::get, Collections.emptyList()));
        values.put("provider.cache.redis.tls", "true");
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(values::get, Collections.emptyList()));
    }
    @Test void detectionVersionChangesNamespaceWhileConnectionChangeRequiresSeparateRestartSignature() {
        Map<String, Object> values = redis();
        values.put("provider.vpn.proxycheck.enabled", true);
        ProviderConfiguration first = new ProviderConfiguration(values::get, Collections.singletonList("proxycheck"));
        values.put("provider.cache.redis.hostname", "cache.internal");
        ProviderConfiguration otherConnection = new ProviderConfiguration(values::get, Collections.singletonList("proxycheck"));
        assertEquals(first.cacheNamespace, otherConnection.cacheNamespace);
        assertNotEquals(first.cacheSignature, otherConnection.cacheSignature);
        values.put("provider.vpn.proxycheck.api-version", "v3");
        ProviderConfiguration v3 = new ProviderConfiguration(values::get, Collections.singletonList("proxycheck"));
        assertNotEquals(first.cacheNamespace, v3.cacheNamespace);
        values.put("provider.vpn.proxycheck.api-key", "synthetic-credential-not-for-service");
        ProviderConfiguration keyed = new ProviderConfiguration(values::get, Collections.singletonList("proxycheck"));
        assertNotEquals(v3.cacheNamespace, keyed.cacheNamespace);
        assertTrue(keyed.cacheNamespace.matches("[0-9a-f]{64}"));
    }
}
