package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.policy.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CloudPolicyVersioningTest {
    @TempDir Path directory;
    private GuardSettings base;
    @BeforeEach void setup() throws Exception {
        ConnectionGuard.shutdown();
        base = GuardSettings.read(key -> key.equals("operation.mode") ? "OBSERVE" : null, Collections.emptyList());
        ConnectionGuard.applySettings(base); ConnectionGuard.initializeRules(directory);
        GuardSettings enforce = GuardSettings.read(key -> key.equals("behavior.vpn.kick-player") ? true : null, Collections.emptyList());
        PolicyReplay.Snapshot candidate = new PolicyReplay.Snapshot(enforce, Collections.emptyList());
        ConnectionGuard.activatePolicy(candidate, candidate.fingerprint(), ConnectionGuard.policyActivationToken());
    }
    @AfterEach void cleanup() { ConnectionGuard.shutdown(); }
    @Test void existingCloudRuleCommandsUseTheSameJournalAndInvalidatePreviouslyReviewedBase() throws Exception {
        String expected = ConnectionGuard.policyActivationToken(); String before = ConnectionGuard.getRuleStore().revision();
        JsonObject command = new JsonObject(); command.addProperty("id", "cmd_AAAAAAAAAAAAAAAA"); command.addProperty("type", "access_rule.add");
        command.addProperty("effect", "ALLOW"); command.addProperty("scope", "VPN"); command.addProperty("target", "192.0.2.12");
        long expiry = System.currentTimeMillis() + 60000; command.addProperty("expires_at", expiry); command.add("note", JsonNull.INSTANCE);
        CloudCommandExecutor.execute("access_rule.add", command);
        AccessRule rule = ConnectionGuard.getRuleStore().snapshot().get(0);
        assertEquals(expiry, rule.getExpiresAt()); assertNotEquals(before, ConnectionGuard.getRuleStore().revision());
        assertEquals(PolicyJournal.Operation.RULES, ConnectionGuard.getRuleStore().journal().current().operation);
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.releasePolicy(expected));
        assertTrue(ConnectionGuard.getRuleStore().locallyOwned());
        assertEquals(ConnectionGuard.policySnapshot().fingerprint(), ConnectionGuard.getRuleStore().journal().current().policy.fingerprint());
    }
    @Test void dashboardSnapshotShowsEffectiveLocalDecisionFieldsAndRedactsExistingSecrets() {
        Map<String, Object> yaml = new HashMap<>(); yaml.put("operation.mode", "OBSERVE"); yaml.put("behavior.vpn.kick-player", false);
        yaml.put("provider.vpn.proxycheck.api-key", "SYNTHETICsecret1234");
        JsonObject snapshot = CloudManagedConfig.snapshot(yaml::get);
        assertEquals("ENFORCE", snapshot.get("operation.mode").getAsString()); assertTrue(snapshot.get("behavior.vpn.kick-player").getAsBoolean());
        assertFalse(snapshot.toString().contains("SYNTHETICsecret")); assertEquals("1234", snapshot.getAsJsonObject("provider.vpn.proxycheck.api-key").get("hint").getAsString());
        String diagnostic = OperationsCommands.doctor().toString(); assertTrue(diagnostic.contains("owner=LOCAL_VERSION"));
        assertTrue(diagnostic.contains(ConnectionGuard.getRuleStore().revision())); assertFalse(diagnostic.contains("192.0.2.12"));
    }
    @Test void managedDecisionFieldsAreDetectedAndConflictingProviderDraftIsRejectedBeforeMutation() throws Exception {
        JsonObject values = new JsonObject(); values.addProperty("operation.mode", "OBSERVE");
        CloudManagedConfig.write(directory, CloudManagedConfig.next(CloudManagedConfig.EMPTY, 1, false, values, null));
        assertTrue(CloudManagedConfig.managesDecisionPolicy(directory));
        Map<String, Object> yaml = new HashMap<>(); yaml.put("operation.mode", "OBSERVE"); yaml.put("provider.geo.service", "Disabled");
        ProviderConfiguration draft = new ProviderConfiguration(yaml::get, Collections.emptyList(), directory);
        assertTrue(draft.cloudManagesPolicy); GuardSettings effective = ConnectionGuard.getSettings(); String revision = ConnectionGuard.getRuleStore().revision();
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuard.applyProviders(draft));
        assertSame(effective, ConnectionGuard.getSettings()); assertEquals(revision, ConnectionGuard.getRuleStore().revision());
        JsonObject operational = new JsonObject(); operational.addProperty("required-positive-flags", 1);
        CloudManagedConfig.write(directory, CloudManagedConfig.next(CloudManagedConfig.EMPTY, 2, false, operational, null));
        assertFalse(CloudManagedConfig.managesDecisionPolicy(directory)); assertFalse(CloudManagedConfig.managesDecisionPolicy(null));
    }
}
