package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CloudManagedConfigTest {
    @TempDir Path dir;

    private static JsonObject json(String s) { return new Gson().fromJson(s, JsonObject.class); }

    @Test void neverAcceptsConsoleCommandsOrConnectionSettings() {
        for (String path : new String[]{"behavior.vpn.execute-command.enabled", "behavior.vpn.execute-command.command",
                "provider.cache.type", "provider.cache.redis.hostname", "identity.trust-forwarded-uuid", "cloud.enabled", "lookup.workers"}) {
            JsonObject values = new JsonObject(); values.addProperty(path, "x");
            assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.parse(values), path);
        }
    }

    @Test void convertsToYamlTypes() {
        Map<String, Object> v = CloudManagedConfig.parse(json("{\"operation.mode\":\"ENFORCE\",\"required-positive-flags\":2.0,"
                + "\"behavior.geo.list\":[\"CN\",\"RU\"],\"behavior.vpn.kick-player\":false,\"behavior.vpn.exemptions\":[\" Notch \"]}"));
        assertEquals("ENFORCE", v.get("operation.mode"));
        assertEquals(2, v.get("required-positive-flags"));
        assertEquals(Arrays.asList("CN", "RU"), v.get("behavior.geo.list"));
        assertEquals(false, v.get("behavior.vpn.kick-player"));
        assertEquals(Collections.singletonList("Notch"), v.get("behavior.vpn.exemptions"));
    }

    @Test void rejectsWrongTypesAndRanges() {
        for (String bad : new String[]{"{\"operation.mode\":\"BLOCK\"}", "{\"required-positive-flags\":0}", "{\"required-positive-flags\":1.5}",
                "{\"behavior.geo.list\":[\"cn\"]}", "{\"behavior.vpn.kick-player\":\"yes\"}", "{\"behavior.vpn.send-webhook.url\":\"http://x\"}",
                "{\"provider.vpn.iphub.api-key\":\"has space\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.parse(json(bad)), bad);
        }
    }

    @Test void overlayLayersValuesAndCarriesKeptSecrets() throws Exception {
        CloudManagedConfig v1 = CloudManagedConfig.next(CloudManagedConfig.EMPTY, 1, false,
                json("{\"provider.vpn.iphub.api-key\":\"secret1234\",\"operation.mode\":\"ENFORCE\"}"), null);
        CloudManagedConfig.write(dir, v1);
        CloudManagedConfig v2 = CloudManagedConfig.next(CloudManagedConfig.load(dir), 2, false,
                json("{\"operation.mode\":\"OBSERVE\"}"), new Gson().fromJson("[\"provider.vpn.iphub.api-key\"]", JsonArray.class));
        assertEquals("secret1234", v2.values.get("provider.vpn.iphub.api-key"));
        assertEquals("OBSERVE", v2.values.get("operation.mode"));
        CloudManagedConfig.write(dir, v2);
        Map<String, Object> yaml = new HashMap<>();
        yaml.put("operation.mode", "ENFORCE");
        CloudManagedConfig.overlay(dir, yaml::put);
        assertEquals("OBSERVE", yaml.get("operation.mode"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(CloudManagedConfig.file(dir))));
        assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.next(CloudManagedConfig.EMPTY, 3, false,
                new JsonObject(), new Gson().fromJson("[\"operation.mode\"]", JsonArray.class)));
    }

    @Test void resetClearsEverything() {
        CloudManagedConfig reset = CloudManagedConfig.next(CloudManagedConfig.EMPTY, 4, true, json("{\"operation.mode\":\"ENFORCE\"}"), null);
        assertTrue(reset.values.isEmpty());
        assertEquals(4, reset.version);
    }

    @Test void snapshotNeverRevealsSecrets() {
        Map<String, Object> effective = new HashMap<>();
        effective.put("provider.vpn.proxycheck.api-key", "abcdefgh1234");
        effective.put("behavior.vpn.send-webhook.url", "");
        effective.put("operation.mode", "OBSERVE");
        effective.put("behavior.geo.list", Arrays.asList("CN"));
        JsonObject snap = CloudManagedConfig.snapshot(effective::get);
        assertEquals("{\"set\":true,\"hint\":\"1234\"}", snap.get("provider.vpn.proxycheck.api-key").toString());
        assertFalse(snap.getAsJsonObject("behavior.vpn.send-webhook.url").get("set").getAsBoolean());
        assertFalse(snap.toString().contains("abcdefgh"));
        assertEquals("OBSERVE", snap.get("operation.mode").getAsString());
    }
    @Test void rejectsCoercedValuesAndDoesNotEchoUnknownPath() {
        for (String bad : new String[]{"{\"provider.vpn.iphub.api-key\":123}", "{\"behavior.vpn.exemptions\":[123]}",
                "{\"behavior.geo.list\":[true]}"}) assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.parse(json(bad)));
        JsonObject values = new JsonObject(); values.addProperty("private-token-4242\nforged", true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.parse(values));
        assertFalse(error.getMessage().contains("private-token")); assertFalse(error.getMessage().contains("\n"));
    }

    @Test void invalidOversizedAndSymlinkOverlayRejectWholeDraft() throws Exception {
        Path file = CloudManagedConfig.file(dir); Files.createDirectories(file.getParent());
        Files.write(file, new byte[128 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.overlay(dir, (key, value) -> fail("must not activate")));
        Path outside = dir.resolve("outside.json"); Files.write(outside, "{\"version\":1,\"values\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(file); Files.createSymbolicLink(file, outside);
        assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.load(dir));
        assertThrows(java.io.IOException.class, () -> CloudManagedConfig.write(dir, CloudManagedConfig.EMPTY));
        assertEquals("{\"version\":1,\"values\":{}}", new String(Files.readAllBytes(outside), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void rollbackAtomicallyRestoresPrivatePreviousBytes() throws Exception {
        CloudManagedConfig original = CloudManagedConfig.next(CloudManagedConfig.EMPTY, 1, false, json("{\"operation.mode\":\"OBSERVE\"}"), null);
        CloudManagedConfig.write(dir, original);
        byte[] previous = CloudManagedConfig.write(dir, CloudManagedConfig.next(original, 2, false, json("{\"operation.mode\":\"ENFORCE\"}"), null));
        CloudManagedConfig.restore(dir, previous);
        assertArrayEquals(previous, Files.readAllBytes(CloudManagedConfig.file(dir)));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(CloudManagedConfig.file(dir))));
        try (java.util.stream.Stream<Path> files = Files.list(CloudManagedConfig.file(dir).getParent())) {
            assertEquals(1, files.count(), "no predictable or retained temporary files");
        }
    }

    @Test void independentlyRejectsMalformedDesiredEnvelopeEvenOnReset() {
        for (String bad : new String[]{
                "{\"version\":\"2\",\"reset\":true,\"values\":{},\"keep_secrets\":[]}",
                "{\"version\":1.5,\"reset\":true,\"values\":{},\"keep_secrets\":[]}",
                "{\"version\":2147483648,\"reset\":true,\"values\":{},\"keep_secrets\":[]}",
                "{\"version\":2,\"reset\":\"true\",\"values\":{},\"keep_secrets\":[]}",
                "{\"version\":2,\"reset\":true,\"values\":{\"identity.trust-forwarded-uuid\":true},\"keep_secrets\":[]}",
                "{\"version\":2,\"reset\":true,\"values\":{},\"keep_secrets\":[\"operation.mode\"]}",
                "{\"version\":2,\"reset\":true,\"values\":{},\"keep_secrets\":[],\"console.execute\":\"op x\"}",
                "{\"version\":2,\"reset\":true,\"values\":{}}"})
            assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.desired(CloudManagedConfig.EMPTY, json(bad)));
        CloudManagedConfig valid = CloudManagedConfig.desired(CloudManagedConfig.EMPTY,
                json("{\"version\":2,\"reset\":false,\"values\":{\"operation.mode\":\"ENFORCE\"},\"keep_secrets\":[]}"));
        assertEquals(2, valid.version); assertEquals("ENFORCE", valid.values.get("operation.mode"));
    }

}
