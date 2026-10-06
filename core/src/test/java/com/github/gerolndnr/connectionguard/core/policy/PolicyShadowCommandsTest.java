package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyShadowCommandsTest {
    @TempDir Path directory;
    private static final String IP = "192.0.2.1";
    private static final String CANDIDATE = "{\"schema\":1,\"mode\":\"ENFORCE\",\"vpn_failure\":\"OPEN\",\"geo_failure\":\"OPEN\",\"kick_vpn\":false,\"kick_geo\":false,\"geo_type\":\"BLACKLIST\",\"countries\":[],\"rules\":[]}";
    @BeforeEach void setup() throws Exception {
        Map<String, Object> fields = new HashMap<>(); fields.put("behavior.vpn.kick-player", true);
        ConnectionGuard.applySettings(GuardSettings.read(fields::get, Collections.emptyList())); ConnectionGuard.initializeRules(directory);
        Files.createDirectory(directory.resolve("policy")); Files.write(directory.resolve("policy/candidate.json"), CANDIDATE.getBytes(StandardCharsets.UTF_8));
    }
    @AfterEach void cleanup() { ConnectionGuard.shutdown(); }
    private List<String> command(String... args) {
        List<String> output = new ArrayList<>(); assertTrue(OperationsCommands.handle(args, permission -> true, output::add)); return output;
    }
    private void positive() {
        ConnectionPolicy.Evaluation live = ConnectionGuard.evaluatePolicy(ConnectionGuard.getSettings(), IP, null, false, false, false,
                new VpnResult(IP, true), new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 0), null, 100);
        assertEquals(Reason.VPN_FLAG, live.denial);
    }
    @Test void explicitStartCountsLiveOnlyAndNeverActivatesOrPersistsTheCandidate() throws Exception {
        GuardSettings before = ConnectionGuard.getSettings(); String stats = ConnectionGuard.lookupStats();
        Map<String, ?> health = new HashMap<>(ConnectionGuard.providerHealth()); byte[] input = Files.readAllBytes(directory.resolve("policy/candidate.json"));
        assertTrue(command("policy", "shadow", "start", "candidate", "5m").get(0).contains("Shadow ACTIVE: compared 0"));
        command("policy", "test"); assertEquals(0, ConnectionGuard.policyShadowStatus().compared);
        positive(); List<String> output = command("policy", "shadow", "status");
        assertTrue(output.get(0).contains("compared 1; changed outcomes: 1"));
        assertFalse(output.toString().contains(IP)); assertFalse(output.toString().contains("00000000-"));
        assertSame(before, ConnectionGuard.getSettings()); assertEquals(stats, ConnectionGuard.lookupStats());
        assertEquals(health, ConnectionGuard.providerHealth()); assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
        assertArrayEquals(input, Files.readAllBytes(directory.resolve("policy/candidate.json")));
        assertFalse(Files.exists(directory.resolve("access-rules.json")));
        assertTrue(command("policy", "shadow", "stop").get(0).contains("Shadow STOPPED")); positive();
        assertEquals(1, ConnectionGuard.policyShadowStatus().compared);
    }
    @Test void unauthorizedStartStopAndStatusCannotReadFilesOrChangeAWindow() {
        for (String action : Arrays.asList("start", "stop", "status")) {
            List<String> output = new ArrayList<>();
            OperationsCommands.handle(new String[]{"policy", "shadow", action, "../secret"}, permission -> false, output::add);
            assertEquals(1, output.size()); assertTrue(output.get(0).contains("permission"));
        }
        command("policy", "shadow", "start", "candidate");
        OperationsCommands.handle(new String[]{"policy", "shadow", "stop"}, permission -> false, ignored -> { });
        assertEquals(PolicyShadow.State.ACTIVE, ConnectionGuard.policyShadowStatus().state);
    }
    @Test void malformedInputUnsupportedWindowTraversalAndSymlinksPreserveActiveSession() throws Exception {
        command("policy", "shadow", "start", "candidate"); positive();
        Files.createSymbolicLink(directory.resolve("policy/linked.json"), directory.resolve("policy/candidate.json"));
        for (String[] args : new String[][]{{"policy", "shadow", "start", "../candidate"},
                {"policy", "shadow", "start", "linked"}, {"policy", "shadow", "start", "candidate", "2h"},
                {"policy", "shadow", "start", "candidate", "5m"}}) {
            List<String> output = command(args); assertEquals(1, output.size()); assertTrue(output.get(0).contains("rejected"));
            assertEquals(PolicyShadow.State.ACTIVE, ConnectionGuard.policyShadowStatus().state); assertEquals(1, ConnectionGuard.policyShadowStatus().compared);
        }
        Files.write(directory.resolve("policy/candidate.json"), CANDIDATE.replace("\"mode\":\"ENFORCE\"", "\"mode\":\"PRIVATE-SECRET\"").getBytes(StandardCharsets.UTF_8));
        List<String> output = command("policy", "shadow", "start", "candidate");
        assertTrue(output.get(0).contains("rejected")); assertFalse(output.get(0).contains("PRIVATE-SECRET"));
        assertEquals(PolicyShadow.State.ACTIVE, ConnectionGuard.policyShadowStatus().state);
    }
    @Test void failedDraftValidationKeepsWindowButSuccessfulReloadEndsIt() {
        command("policy", "shadow", "start", "candidate"); positive();
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(path -> path.equals("operation.mode") ? "INVALID" : null, Collections.emptyList()));
        assertEquals(PolicyShadow.State.ACTIVE, ConnectionGuard.policyShadowStatus().state);
        ConnectionGuard.applySettings(ConnectionGuard.getSettings());
        assertEquals(PolicyShadow.State.BASE_CHANGED, ConnectionGuard.policyShadowStatus().state);
        positive(); assertEquals(1, ConnectionGuard.policyShadowStatus().compared);
    }
    @Test void durableManualOrCloudRuleChangeStopsBeforeTheNextComparison() throws Exception {
        command("policy", "shadow", "start", "candidate"); positive();
        ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.VPN, "198.51.100.1", 0, "Synthetic unrelated rule");
        positive(); assertEquals(PolicyShadow.State.BASE_CHANGED, ConnectionGuard.policyShadowStatus().state);
        assertEquals(1, ConnectionGuard.policyShadowStatus().compared);
    }
    @Test void providerThresholdChangeAndShutdownEndTheComparison() {
        command("policy", "shadow", "start", "candidate"); positive();
        ConnectionGuard.setRequiredPositiveFlags(2);
        assertEquals(PolicyShadow.State.BASE_CHANGED, ConnectionGuard.policyShadowStatus().state);
        command("policy", "shadow", "start", "candidate"); ConnectionGuard.shutdown();
        assertEquals(PolicyShadow.State.STOPPED, ConnectionGuard.policyShadowStatus().state);
        ConnectionGuard.setRequiredPositiveFlags(1);
    }
}
