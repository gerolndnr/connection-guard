package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class KeylessProviderConfigurationTest {
    @TempDir Path directory;
    private static Object value(Map<String, Object> map, String path) {
        Object result = map; for (String part : path.split("\\.")) result = result instanceof Map ? ((Map<?, ?>) result).get(part) : null;
        return result;
    }
    @SuppressWarnings("unchecked") private static List<String> keys(Map<String, Object> map) { return new ArrayList<>(((Map<String, Object>) value(map, "provider.vpn")).keySet()); }
    @Test void actualNewInstallYamlSelectsTheRequestedOrderGeoOffAndEstablishedLimits() {
        Map<String, Object> yaml = new Yaml().load(getClass().getResourceAsStream("/config.yml"));
        ProviderConfiguration draft = new ProviderConfiguration(path -> value(yaml, path), keys(yaml));
        assertEquals(Arrays.asList("connectionguard-intel", "proxycheck", "blackbox", "ipcheck", "zowi", "ipquery", "ip-api"), draft.keys);
        assertNull(draft.geo); assertFalse(draft.settings.observe); assertEquals(5000, draft.settings.lookup.deadlineMillis);
        assertEquals(1500, draft.settings.lookup.httpTimeoutMillis); assertEquals(8, draft.settings.lookup.workers);
        assertEquals(64, draft.settings.lookup.queueCapacity); assertEquals(128, draft.settings.lookup.maxInflight); assertEquals(30000, draft.settings.lookup.circuitPauseMillis);
        for (int pos = 2; pos <= 4; pos++) {
            String id = draft.keys.get(pos); assertEquals(id, ConnectionGuard.providerId(draft.providers.get(pos), pos));
            assertEquals(Integer.valueOf(60), draft.minuteBudgets.get(id)); assertEquals("vpn."+id, draft.healthIds.get(id));
        }
    }
    @Test void oldSelectionsAndOrderAreRetainedAndTheNoticeNeverRewritesTheFile() throws Exception {
        String original = "# old operator file\noperation:\n  mode: OBSERVE\nprovider:\n  vpn:\n    ipquery:\n      enabled: true\n    proxycheck:\n      enabled: true\n    ip-api:\n      enabled: true\n  vpn-failover:\n    order: [ipquery, proxycheck, ip-api]\n";
        Path config = directory.resolve("config.yml"); byte[] bytes = original.getBytes(StandardCharsets.UTF_8); Files.write(config, bytes);
        Map<String, Object> yaml = new Yaml().load(original);
        ProviderConfiguration draft = new ProviderConfiguration(path -> value(yaml, path), keys(yaml), directory.toRealPath());
        assertTrue(draft.settings.observe); assertEquals(Arrays.asList("ipquery", "proxycheck", "ip-api"), draft.keys);
        List<String> messages = new ArrayList<>(); Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() { public void publish(LogRecord record) { messages.add(record.getMessage()); } public void flush() { } public void close() { } });
        KeylessProviderNotice.show(directory, true, logger); KeylessProviderNotice.show(directory, true, logger);
        assertEquals(1, messages.size()); assertTrue(messages.get(0).contains("your configured providers and order are unchanged"));
        assertTrue(messages.get(0).contains("player IPs")); assertArrayEquals(bytes, Files.readAllBytes(config));
        assertEquals(draft.keys, new ProviderConfiguration(path -> value(new Yaml().load(original), path), keys(yaml)).keys);
    }
    @Test void noNewRecipientIsActivatedEvenIfAnOldLoaderListsAbsentOrDisabledSections() {
        Map<String, Object> old = new HashMap<>(); old.put("provider.vpn.proxycheck.enabled", true); old.put("provider.vpn.ipquery.enabled", true);
        ProviderConfiguration draft = new ProviderConfiguration(old::get, Arrays.asList("proxycheck", "ipquery", "blackbox", "ipcheck", "zowi"));
        assertEquals(Arrays.asList("proxycheck", "ipquery"), draft.keys);
        old.put("provider.vpn.blackbox.enabled", false); assertEquals(draft.keys, new ProviderConfiguration(old::get, Arrays.asList("proxycheck", "ipquery", "blackbox")).keys);
    }
    @Test void newInstallConsumesTheOnceNoticeMarkerWithoutLaterUpgradeNoise() throws Exception {
        List<String> messages = new ArrayList<>(); Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() { public void publish(LogRecord record) { messages.add(record.getMessage()); } public void flush() { } public void close() { } });
        KeylessProviderNotice.show(directory, false, logger); KeylessProviderNotice.show(directory, true, logger);
        assertTrue(messages.isEmpty()); assertTrue(Files.exists(directory.resolve("keyless-providers-v052.notice")));
    }
}
