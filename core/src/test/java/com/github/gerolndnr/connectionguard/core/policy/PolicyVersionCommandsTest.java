package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.extensions.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyVersionCommandsTest {
    @TempDir Path directory;
    private GuardSettings base;
    private PolicyReplay.Snapshot candidate;
    @BeforeEach void setup() throws Exception {
        ConnectionGuard.shutdown(); ConnectionGuard.setRequiredPositiveFlags(1);
        Map<String, Object> values = new HashMap<>(); values.put("operation.mode", "OBSERVE");
        values.put("lookup.workers", 3); values.put("behavior.vpn.kick-player", true);
        base = GuardSettings.read(values::get, Collections.emptyList()); ConnectionGuard.applySettings(base); ConnectionGuard.initializeRules(directory);
        values.put("operation.mode", "ENFORCE");
        candidate = new PolicyReplay.Snapshot(GuardSettings.read(values::get, Collections.emptyList()), Collections.emptyList());
        Files.createDirectory(directory.resolve("policy"));
        Files.write(directory.resolve("policy/candidate.json"), candidate.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }
    @AfterEach void cleanup() { ConnectionGuard.shutdown(); ConnectionGuard.setRequiredPositiveFlags(1); }
    private List<String> command(String... args) {
        List<String> output = new ArrayList<>(); assertTrue(OperationsCommands.handle(args, permission -> true, output::add)); return output;
    }
    private String activate() throws Exception { return ConnectionGuard.activatePolicy(candidate, candidate.fingerprint(), ConnectionGuard.policyActivationToken()); }
    private Reason denial(GuardSettings settings) {
        return ConnectionGuard.evaluatePolicy(settings, "192.0.2.1", null, false, false, false, new VpnResult("192.0.2.1", true),
                new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 0), null, System.currentTimeMillis()).denial;
    }
    private DecisionCapture capture() { return DecisionCapture.begin(Platform.VELOCITY, Phase.LOGIN, "192.0.2.1", null, IdentityTrust.UNTRUSTED); }
    @Test void inspectAndStatusAreReadOnlyAndExplicitActivationRollbackReleaseChangeTheRealDecision() throws Exception {
        String expected = ConnectionGuard.policyActivationToken(), fingerprint = ConnectionGuard.policySnapshot().fingerprint();
        assertTrue(command("policy", "inspect", "candidate").get(0).contains(candidate.fingerprint()));
        assertTrue(command("policy", "status").toString().contains("owner=CONFIG"));
        assertFalse(Files.exists(directory.resolve("access-rules.json"))); assertNull(denial(base));
        List<String> activated = command("policy", "activate", "candidate", candidate.fingerprint(), expected);
        assertTrue(activated.get(0).startsWith("Policy committed:")); assertTrue(activated.toString().contains("owner=LOCAL_VERSION"));
        GuardSettings effective = ConnectionGuard.getSettings(); assertEquals(Reason.VPN_FLAG, denial(effective));
        assertSame(base.lookup, effective.lookup); assertSame(base.webhooks, effective.webhooks);
        assertSame(base.admission, effective.admission); assertSame(base.admissionHooks, effective.admissionHooks);
        PolicyJournal history = ConnectionGuard.getRuleStore().journal(); String original = history.revisions.get(1).id;
        assertEquals(fingerprint, history.find(original).policy.fingerprint());
        assertTrue(command("policy", "rollback", original, ConnectionGuard.policyActivationToken()).get(0).startsWith("Policy committed:"));
        assertNull(denial(ConnectionGuard.getSettings())); assertTrue(ConnectionGuard.getRuleStore().locallyOwned());
        assertTrue(command("policy", "release", ConnectionGuard.policyActivationToken()).get(0).startsWith("Policy committed:"));
        assertSame(base, ConnectionGuard.getSettings()); assertFalse(ConnectionGuard.getRuleStore().locallyOwned());
        assertEquals(3, ConnectionGuard.getRuleStore().journal().revisions.size());
    }
    @Test void reviewedCandidateAndCurrentBaseMustBothMatchIncludingRuleAndProviderConditionChanges() throws Exception {
        String expected = ConnectionGuard.policyActivationToken();
        ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.GEO, "198.51.100.1", 0, "Synthetic staff edit");
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.activatePolicy(candidate, candidate.fingerprint(), expected));
        String fresh = ConnectionGuard.policyActivationToken(); ConnectionGuard.setRequiredPositiveFlags(2);
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.activatePolicy(candidate, candidate.fingerprint(), fresh));
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.activatePolicy(candidate, "wrong", ConnectionGuard.policyActivationToken()));
        assertSame(base, ConnectionGuard.getSettings()); assertNull(ConnectionGuard.getRuleStore().journal());
        byte[] rules = Files.readAllBytes(directory.resolve("access-rules.json"));
        Files.write(directory.resolve("policy/candidate.json"), candidate.toJson().toString().replace("ENFORCE", "OBSERVE").getBytes(StandardCharsets.UTF_8));
        List<String> rejected = command("policy", "activate", "candidate", candidate.fingerprint(), ConnectionGuard.policyActivationToken());
        assertEquals(ConnectionGuard.getMessages().getString("ops.policy-version-rejected"), rejected.get(0));
        assertArrayEquals(rules, Files.readAllBytes(directory.resolve("access-rules.json"))); assertNull(ConnectionGuard.getRuleStore().journal());
    }
    @Test void readPermissionDoesNotPermitAnyWritesEvenBeforeOpeningAnInvalidPath() {
        for (String action : Arrays.asList("activate", "rollback", "release")) {
            List<String> output = new ArrayList<>();
            OperationsCommands.handle(new String[]{"policy", action, "../secret", "PRIVATE", "PRIVATE"},
                    permission -> permission.equals("connectionguard.command.policy"), output::add);
            assertEquals(Collections.singletonList(ConnectionGuard.getMessages().getString("ops.permission")), output);
        }
        assertFalse(Files.exists(directory.resolve("access-rules.json"))); assertSame(base, ConnectionGuard.getSettings());
    }
    @Test void pendingAdmissionWithoutObserversOrHttpBlocksAllVersionTransitionsAndReinitialization() throws Exception {
        String revision = activate(); String expected = ConnectionGuard.policyActivationToken();
        DecisionCapture pending = capture();
        try {
            assertEquals(1, ConnectionGuard.activePolicyDecisions());
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.activatePolicy(candidate, candidate.fingerprint(), expected));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.rollbackPolicy(revision, expected));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.releasePolicy(expected));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.initializeRules(directory));
            assertThrows(java.io.IOException.class, () -> ConnectionGuard.getRuleStore().reload());
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.getRuleStore().transition(candidate, PolicyJournal.Operation.ACTIVATE, true, 10));
            ProviderConfiguration same = new ProviderConfiguration(key -> key.equals("provider.geo.service") ? "Disabled" : null, Collections.emptyList());
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.applyProviders(same));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setRequiredPositiveFlags(2));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setVpnProviders(new ArrayList<>()));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setFailover(false, 16));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setGeoProvider(null));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setCacheProvider(null));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setVpnCacheExpirationTime(1));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setGeoCacheExpirationTime(1));
            assertEquals(revision, ConnectionGuard.getRuleStore().revision());
            pending.error(); pending.denied(Reason.EXTERNAL_POLICY);
        } finally { pending.close(); pending.close(); }
        assertEquals(0, ConnectionGuard.activePolicyDecisions()); assertDoesNotThrow(() -> ConnectionGuard.releasePolicy(expected));
    }
    @Test void shutdownAndDuplicateOldCallbacksNeverConsumeANewGenerationsLease() throws Exception {
        DecisionCapture old = capture(); assertEquals(1, ConnectionGuard.activePolicyDecisions());
        ConnectionGuard.shutdown(); ConnectionGuard.initializeRules(directory); DecisionCapture current = capture();
        old.close(); old.close(); assertEquals(1, ConnectionGuard.activePolicyDecisions());
        assertThrows(IllegalStateException.class, this::activate); current.close(); assertEquals(0, ConnectionGuard.activePolicyDecisions()); activate();
    }
    @Test void concurrentBeginVersusActivationNeverCapturesHalfARevision() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2); ExecutorService executor = Executors.newFixedThreadPool(2);
        String expected = ConnectionGuard.policyActivationToken();
        PolicyReplay.Snapshot withDeny = new PolicyReplay.Snapshot(candidate.settings, Collections.singletonList(
                new AccessRule("atomic-deny", AccessRule.Effect.DENY, AccessRule.Scope.GEO, "192.0.2.1", 0, "Synthetic atomic rule")));
        try {
            Future<DecisionCapture> captured = executor.submit(() -> { barrier.await(); return capture(); });
            Future<Boolean> committed = executor.submit(() -> { barrier.await();
                try { ConnectionGuard.activatePolicy(withDeny, withDeny.fingerprint(), expected); return true; }
                catch (IllegalStateException busy) { return false; }
            });
            try (DecisionCapture active = captured.get(2, TimeUnit.SECONDS)) {
                boolean changed = committed.get(2, TimeUnit.SECONDS);
                assertEquals(!changed, active.observe());
                assertEquals(changed ? Reason.ACCESS_RULE : null, denial(active.settings()));
                assertEquals(changed ? 1 : 0, ConnectionGuard.getRuleStore().snapshot().size());
                assertEquals(1, ConnectionGuard.activePolicyDecisions());
            }
            assertEquals(0, ConnectionGuard.activePolicyDecisions());
        } finally { executor.shutdownNow(); }
    }
    @Test void localOwnerRejectsChangedNativeDecisionFieldsAndRestartLoadsCommittedBehavior() throws Exception {
        String revision = activate();
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.applySettings(candidate.settings));
        assertDoesNotThrow(() -> ConnectionGuard.applySettings(base)); assertEquals(Reason.VPN_FLAG, denial(ConnectionGuard.getSettings()));
        ConnectionGuard.shutdown(); ConnectionGuard.applySettings(base); ConnectionGuard.initializeRules(directory);
        assertEquals(revision, ConnectionGuard.getRuleStore().revision()); assertEquals(Reason.VPN_FLAG, denial(ConnectionGuard.getSettings()));
    }
    @Test void sharedLateDenyStillUpdatesBothScopesWhileVersionAndSourceChangesWaitForTheLogin() throws Exception {
        activate();
        try (DecisionCapture pending = capture()) {
            ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "192.0.2.1", 0, "Synthetic late deny");
            assertEquals(Reason.ACCESS_RULE, denial(pending.settings()));
            assertEquals(PolicyJournal.Operation.RULES, ConnectionGuard.getRuleStore().journal().current().operation);
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.setRequiredPositiveFlags(2));
            assertEquals(1, ConnectionGuard.activePolicyDecisions());
        }
        assertEquals(0, ConnectionGuard.activePolicyDecisions());
    }
}
