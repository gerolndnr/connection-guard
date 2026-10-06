package com.github.gerolndnr.connectionguard.core.rules;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.policy.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AccessRuleVersioningTest {
    @TempDir Path directory;
    private final GuardSettings configured = GuardSettings.defaults();
    private GuardSettings enforced() {
        Map<String, Object> fields = new HashMap<>(); fields.put("behavior.vpn.kick-player", true);
        fields.put("failure-policy.geo", "CLOSED"); fields.put("behavior.geo.type", "WHITELIST"); fields.put("behavior.geo.list", Arrays.asList("DE"));
        return GuardSettings.read(fields::get, Collections.emptyList());
    }
    private PolicyReplay.Snapshot candidate(List<AccessRule> rules) { return new PolicyReplay.Snapshot(enforced(), rules); }
    @Test void migrationAndRestartCommitOnePolicyDocumentAndPreserveOriginalLegacyBytes() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        AccessRule grant = store.add(AccessRule.Effect.ALLOW, AccessRule.Scope.GEO, "2001:db8::1", System.currentTimeMillis() + 60000, "Synthetic legacy");
        byte[] legacy = Files.readAllBytes(directory.resolve("access-rules.json"));
        PolicyJournal committed = store.transition(candidate(store.snapshot()), PolicyJournal.Operation.ACTIVATE, true, 10);
        assertArrayEquals(legacy, Files.readAllBytes(directory.resolve("access-rules.before-policy.json")));
        AccessRuleStore restarted = new AccessRuleStore(directory);
        assertEquals(committed.current().id, restarted.revision()); assertTrue(restarted.locallyOwned());
        assertTrue(restarted.effective(configured).kickVpn); assertEquals(GuardSettings.FailurePolicy.CLOSED, restarted.effective(configured).geoFailure);
        assertTrue(restarted.effective(configured).geoWhitelist); assertEquals(Collections.singletonList("DE"), restarted.effective(configured).countries);
        assertEquals(grant.getExpiresAt(), restarted.snapshot().get(0).getExpiresAt());
        assertSame(configured.lookup, restarted.effective(configured).lookup);
        assertSame(configured.admission, restarted.effective(configured).admission);
        assertSame(configured.webhooks, restarted.effective(configured).webhooks);
        assertSame(configured.admissionHooks, restarted.effective(configured).admissionHooks);
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("access-rules.json"))));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("access-rules.before-policy.json"))));
    }
    @Test void failedPersistPreservesMemoryDiskAndRevisionEvenWhenBackupHasBeenCreated() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        AccessRuleStore store = new AccessRuleStore(directory, new Object(), () -> configured, (path, bytes) -> {
            if (fail.get() && path.getFileName().toString().equals("access-rules.json")) throw new IOException("Synthetic rename failure");
            AccessRuleStore.atomicWrite(path, bytes);
        });
        store.add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "Synthetic original");
        byte[] disk = Files.readAllBytes(directory.resolve("access-rules.json")); List<AccessRule> before = store.snapshot(); String revision = store.revision();
        fail.set(true);
        assertThrows(IOException.class, () -> store.transition(candidate(Collections.emptyList()), PolicyJournal.Operation.ACTIVATE, true, 10));
        assertSame(before, store.snapshot()); assertSame(configured, store.effective(configured)); assertEquals(revision, store.revision());
        assertNull(store.journal()); assertArrayEquals(disk, Files.readAllBytes(directory.resolve("access-rules.json")));
        assertArrayEquals(disk, Files.readAllBytes(directory.resolve("access-rules.before-policy.json")));
        fail.set(false); store.transition(candidate(before), PolicyJournal.Operation.ACTIVATE, true, 11);
        byte[] activated = Files.readAllBytes(directory.resolve("access-rules.json")); String activeRevision = store.revision();
        fail.set(true);
        assertThrows(IOException.class, () -> store.remove(before.get(0).getId()));
        assertThrows(IOException.class, () -> store.transition(new PolicyReplay.Snapshot(configured, before), PolicyJournal.Operation.RELEASE, false, 12));
        assertTrue(store.locallyOwned()); assertEquals(activeRevision, store.revision()); assertSame(before.get(0), store.snapshot().get(0));
        assertArrayEquals(activated, Files.readAllBytes(directory.resolve("access-rules.json")));
    }
    @Test void externalEditsCorruptReloadAndLegacyReplacementNeverSilentlyOverwriteTheActiveVersion() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        store.transition(candidate(Collections.emptyList()), PolicyJournal.Operation.ACTIVATE, true, 10);
        byte[] active = Files.readAllBytes(directory.resolve("access-rules.json")); String revision = store.revision();
        for (String invalid : Arrays.asList("not json", "[]", "{\"schema\":1}")) {
            Files.write(directory.resolve("access-rules.json"), invalid.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, store::reload);
            assertThrows(IOException.class, () -> store.add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "Synthetic"));
            assertTrue(store.locallyOwned()); assertEquals(revision, store.revision()); assertTrue(store.effective(configured).kickVpn);
        }
        Files.write(directory.resolve("access-rules.json"), active); store.reload(); assertEquals(revision, store.revision());
    }
    @Test void sharedRuleWritersVersionEachEditAndRollbackDoesNotExtendOrReactivateExpiredGrants() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        AccessRule expired = new AccessRule("expired", AccessRule.Effect.ALLOW, AccessRule.Scope.VPN, "192.0.2.1", 1, "Synthetic expired");
        PolicyJournal initial = store.transition(candidate(Collections.singletonList(expired)), PolicyJournal.Operation.ACTIVATE, true, 10);
        String before = store.revision();
        AccessRule deny = store.add(AccessRule.Effect.DENY, AccessRule.Scope.GEO, "192.0.2.2", 0, "Synthetic deny");
        assertNotEquals(before, store.revision()); assertEquals(PolicyJournal.Operation.RULES, store.journal().current().operation);
        assertTrue(store.remove(deny.getId())); assertEquals(3, store.journal().revisions.size());
        assertEquals(1, store.pruneExpired(System.currentTimeMillis())); assertTrue(store.snapshot().isEmpty());
        store.transition(initial.current().policy, PolicyJournal.Operation.ROLLBACK, true, 11);
        assertEquals(1, store.snapshot().get(0).getExpiresAt()); assertFalse(store.match("192.0.2.1", null, false, AccessRule.Scope.VPN).isPresent());
        AccessRuleStore restarted = new AccessRuleStore(directory); assertFalse(restarted.match("192.0.2.1", null, false, AccessRule.Scope.VPN).isPresent());
        assertEquals(3, restarted.journal().revisions.size());
    }
    @Test void configDecisionConflictsAreRejectedWhileOperationalReloadRemainsAvailable() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        store.transition(candidate(Collections.emptyList()), PolicyJournal.Operation.ACTIVATE, true, 10);
        assertThrows(IllegalArgumentException.class, () -> store.validateConfigReload(enforced(), false));
        assertThrows(IllegalArgumentException.class, () -> store.validateConfigReload(configured, true));
        assertDoesNotThrow(() -> store.validateConfigReload(configured, false));
        store.transition(new PolicyReplay.Snapshot(configured, store.snapshot()), PolicyJournal.Operation.RELEASE, false, 11);
        assertFalse(store.locallyOwned()); assertSame(configured, store.effective(configured));
        assertDoesNotThrow(() -> store.validateConfigReload(enforced(), true));
    }
    @Test void symlinkAndNonRegularTargetsCannotBecomeACommittedDocument() throws Exception {
        Path original = directory.resolve("original"); Files.write(original, "[]".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(directory.resolve("access-rules.json"), original);
        assertThrows(IOException.class, () -> new AccessRuleStore(directory));
        assertThrows(IOException.class, () -> AccessRuleStore.atomicWrite(directory.resolve("access-rules.json"), new byte[]{1}));
        assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(original));
        Files.delete(directory.resolve("access-rules.json")); Files.createDirectory(directory.resolve("access-rules.json"));
        assertThrows(IOException.class, () -> AccessRuleStore.atomicWrite(directory.resolve("access-rules.json"), new byte[]{1}));
    }
    @Test void interruptedStagingIsBoundedlyDiscardedAndNeverRestoredAsPolicy() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        store.transition(candidate(Collections.emptyList()), PolicyJournal.Operation.ACTIVATE, true, 10);
        String committed = store.revision(); byte[] before = Files.readAllBytes(directory.resolve("access-rules.json"));
        Files.write(directory.resolve(".cg-policy-123.tmp"), "partial-private-staging".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve(".cg-policy-operator.tmp"), "operator-owned".getBytes(StandardCharsets.UTF_8));
        AccessRuleStore restored = new AccessRuleStore(directory);
        assertEquals(committed, restored.revision()); assertFalse(Files.exists(directory.resolve(".cg-policy-123.tmp")));
        assertTrue(Files.exists(directory.resolve(".cg-policy-operator.tmp"))); assertArrayEquals(before, Files.readAllBytes(directory.resolve("access-rules.json")));
        for (int n = 0; n < 33; n++) Files.write(directory.resolve(".cg-policy-" + n + ".tmp"), new byte[]{1});
        assertThrows(IOException.class, () -> new AccessRuleStore(directory));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("access-rules.json")));
        assertEquals(committed, restored.revision());
    }
}
