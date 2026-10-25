package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A rejected /cg reload keeps both the active rules and the active settings. */
class ReloadAtomicityTest {
    private Object savedRules, savedSignature, savedDraft;

    private static Field field(String name) throws Exception {
        Field f = ConnectionGuard.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    @BeforeEach void save() throws Exception {
        savedRules = field("rules").get(null); savedSignature = field("activeCacheSignature").get(null); savedDraft = field("activeDraft").get(null);
        field("activeCacheSignature").set(null, null);
    }
    @AfterEach void restore() throws Exception {
        field("rules").set(null, savedRules); field("activeCacheSignature").set(null, savedSignature); field("activeDraft").set(null, savedDraft);
    }
    private static ProviderConfiguration draft(String cache) {
        Map<String, Object> values = new HashMap<>();
        values.put("provider.cache.type", cache); values.put("provider.geo.service", "Disabled");
        return new ProviderConfiguration(values::get, Collections.emptyList());
    }
    private static int activeRules() { return ConnectionGuard.getRuleStore().snapshot().size(); }

    @Test void handEditedRulesStayInactiveWhenTheCacheChangeRejectsTheReload(@TempDir Path directory) throws Exception {
        ConnectionGuard.initializeRules(directory);
        ConnectionGuard.applyProviders(draft("Memory"));
        ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "test");
        ProviderConfiguration active = ConnectionGuard.getActiveDraft();
        Files.write(directory.resolve("access-rules.json"), "[]".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.applyProviders(draft("SQLite")));
        assertTrue(rejected.getMessage().contains("restart"));
        assertEquals(1, activeRules());
        assertSame(active, ConnectionGuard.getActiveDraft());

        // The same edit is picked up by a reload that passes.
        ConnectionGuard.applyProviders(draft("Memory"));
        assertEquals(0, activeRules());
    }

    @Test void deletedRulesFileStaysInactiveWhenTheReloadIsRejected(@TempDir Path directory) throws Exception {
        ConnectionGuard.initializeRules(directory);
        ConnectionGuard.applyProviders(draft("Memory"));
        ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "test");
        Files.delete(directory.resolve("access-rules.json"));

        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.applyProviders(draft("SQLite")));
        assertEquals(1, activeRules());
        ConnectionGuard.applyProviders(draft("Memory"));
        assertEquals(0, activeRules());
    }

    @Test void invalidRulesFileRejectsTheReloadWithoutTouchingSettings(@TempDir Path directory) throws Exception {
        ConnectionGuard.initializeRules(directory);
        ConnectionGuard.applyProviders(draft("Memory"));
        ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "test");
        ProviderConfiguration active = ConnectionGuard.getActiveDraft();
        Files.write(directory.resolve("access-rules.json"), "{not json".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.applyProviders(draft("Memory")));
        assertEquals(1, activeRules());
        assertSame(active, ConnectionGuard.getActiveDraft());
    }

    @Test void stagedRulesArePublishedOnlyIfNothingChangedSinceStaging(@TempDir Path directory) throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        store.add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "test");
        assertNull(store.stageIfChanged());
        Files.write(directory.resolve("access-rules.json"), "[]".getBytes(StandardCharsets.UTF_8));
        AccessRuleStore.Staged staged = store.stageIfChanged();
        assertNotNull(staged);
        assertEquals(1, store.snapshot().size());
        // Another edit after staging: the staged state no longer matches the file.
        Files.write(directory.resolve("access-rules.json"), " [] ".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> store.publish(staged));
        assertEquals(1, store.snapshot().size());
        AccessRuleStore.Staged again = store.stageIfChanged();
        store.publish(again);
        assertTrue(store.snapshot().isEmpty());
        store.publish(null);
    }
}
