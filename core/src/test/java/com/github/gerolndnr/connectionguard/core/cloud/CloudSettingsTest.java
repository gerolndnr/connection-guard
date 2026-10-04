package com.github.gerolndnr.connectionguard.core.cloud;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudSettingsTest {
    private static CloudSettings read(Map<String, Object> config, Map<String, String> env, boolean marker) {
        return CloudSettings.read(config::get, env::get, marker);
    }

    @Test void onByDefault() {
        CloudSettings s = read(new HashMap<>(), new HashMap<>(), false);
        assertTrue(s.enabled);
        assertEquals("https://api.connectionguard.net", s.endpoint.toString());
    }

    @Test void everyOptOutWins() {
        Map<String, Object> off = new HashMap<>(); off.put("cloud.enabled", false);
        assertFalse(read(off, new HashMap<>(), false).enabled);
        Map<String, String> env = new HashMap<>(); env.put(CloudSettings.ENV_SWITCH, "false");
        assertEquals("environment variable CONNECTIONGUARD_CLOUD", read(new HashMap<>(), env, false).disabledBy);
        Map<String, String> prop = new HashMap<>(); prop.put(CloudSettings.PROPERTY_SWITCH, "0");
        assertFalse(read(new HashMap<>(), prop, false).enabled);
        assertEquals("/cg cloud disable", read(new HashMap<>(), new HashMap<>(), true).disabledBy);
    }

    @Test void httpsOnlyExceptLoopback() {
        Map<String, Object> plain = new HashMap<>(); plain.put("cloud.endpoint", "http://cloud.example.com");
        assertThrows(IllegalArgumentException.class, () -> read(plain, new HashMap<>(), false));
        Map<String, Object> creds = new HashMap<>(); creds.put("cloud.endpoint", "https://user:pw@cloud.example.com");
        assertThrows(IllegalArgumentException.class, () -> read(creds, new HashMap<>(), false));
        Map<String, Object> local = new HashMap<>(); local.put("cloud.endpoint", "http://127.0.0.1:8788/");
        assertEquals("http://127.0.0.1:8788", read(local, new HashMap<>(), false).endpoint.toString());
        Map<String, Object> self = new HashMap<>(); self.put("cloud.endpoint", "https://guard.example.org");
        assertTrue(read(self, new HashMap<>(), false).enabled);
    }

    @Test void rejectsMalformedNetworkToken() {
        Map<String, Object> bad = new HashMap<>(); bad.put("cloud.network-token", "not-a-token");
        assertThrows(IllegalArgumentException.class, () -> read(bad, new HashMap<>(), false));
    }
}
