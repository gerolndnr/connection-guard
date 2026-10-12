package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyObservationCaptureTest {
    @TempDir Path directory;
    private static final String LIVE_IP = "198.51.100.27";
    private static final UUID LIVE_UUID = UUID.fromString("c80703d0-689a-4ea7-96ed-685977a2c4ea");
    private static final String CANDIDATE = "{\"schema\":1,\"mode\":\"ENFORCE\",\"vpn_failure\":\"OPEN\",\"geo_failure\":\"OPEN\",\"kick_vpn\":true,\"kick_geo\":true,\"geo_type\":\"BLACKLIST\",\"countries\":[],\"rules\":[]}";

    @BeforeEach void setup() { ConnectionGuard.shutdown(); ConnectionGuard.applySettings(GuardSettings.defaults()); ConnectionGuard.initializeRules(directory); }
    @AfterEach void cleanup() { ConnectionGuard.shutdown(); }

    private List<String> command(String... args) {
        List<String> output = new ArrayList<>();
        assertTrue(OperationsCommands.handle(args, permission -> true, output::add));
        return output;
    }
    private VpnResult vpn(boolean positive) {
        VpnResult result = new VpnResult(LIVE_IP, positive);
        result.setPositiveThreshold(1); result.setCachedOn(100); result.setValidUntil(System.currentTimeMillis() + 600_000);
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class); types.put(DetectionDetails.Type.VPN, positive);
        DetectionDetails details = DetectionDetails.withExactRisk(types, 64512L, "Example ISP", "Example VPN", "DE",
                new java.math.BigDecimal("75.5"), 91);
        ProviderVote vote = new ProviderVote("fixture.provider", positive ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE,
                FailureReason.NONE, 12, details, System.currentTimeMillis() + 600_000, null, true);
        result.setVotes(Collections.singletonList(vote));
        return result;
    }
    private GeoLookup geo() {
        GeoResult result = new GeoResult(LIVE_IP, "DE", "PRIVATE-CITY-NAME", "Example ISP");
        result.setAsn(64512L); result.setCachedOn(100); result.setValidUntil(System.currentTimeMillis() + 600_000);
        return new GeoLookup(Optional.of(result), FailureReason.NONE, false, 8);
    }
    private ConnectionPolicy.Evaluation evaluate(UUID uuid, boolean trusted, boolean vpnExempt, boolean geoExempt) {
        long now = System.currentTimeMillis();
        return ConnectionGuard.evaluatePolicy(ConnectionGuard.getSettings(), LIVE_IP, uuid, trusted, vpnExempt, geoExempt,
                vpn(true), geo(), "geo.fixture", now);
    }

    @Test void captureIsOffUntilExplicitStartAndDoesNotCreateAFileOrDirectory() throws Exception {
        evaluate(LIVE_UUID, true, false, false);
        assertFalse(Files.exists(directory.resolve("policy")));
        List<String> status = command("policy", "capture", "status");
        assertTrue(status.get(0).contains("DISABLED"), status.toString());
        assertFalse(status.get(0).contains("saved file=yes"));
        assertFalse(Files.exists(directory.resolve("policy")));
    }

    @Test void explicitlyCapturedFactsAreRedactedPersistedAndReplayedWithoutProviderCalls() throws Exception {
        String lookupStats = ConnectionGuard.lookupStats();
        List<String> started = command("policy", "capture", "start", "5m");
        assertTrue(started.get(0).contains("ACTIVE"), started.toString());
        evaluate(LIVE_UUID, true, false, false);
        assertFalse(Files.exists(directory.resolve("policy/observations.json")));
        assertTrue(command("policy", "capture", "stop").get(0).contains("Saved 1 local replay cases"));

        Path saved = directory.resolve("policy/observations.json");
        String contents = new String(Files.readAllBytes(saved), StandardCharsets.UTF_8);
        assertTrue(contents.contains("saved_observations")); assertTrue(contents.contains(PolicyReplay.REDACTED_ADDRESS));
        assertTrue(contents.contains("Example VPN")); assertFalse(contents.contains(LIVE_IP));
        assertFalse(contents.contains(LIVE_UUID.toString())); assertFalse(contents.contains("PRIVATE-CITY-NAME"));
        assertTrue(command("policy", "capture", "status").get(0).contains("saved file=yes"));

        Files.write(directory.resolve("policy/candidate.json"), CANDIDATE.getBytes(StandardCharsets.UTF_8));
        List<String> output = command("policy", "test", "observations", "candidate");
        assertTrue(output.get(0).contains("Saved local observations: 1"), output.toString());
        assertEquals(lookupStats, ConnectionGuard.lookupStats());
        assertNull(ConnectionGuard.evaluatePolicy(GuardSettings.defaults(), LIVE_IP, null, false, false, false,
                vpn(true), geo(), "geo.fixture", System.currentTimeMillis()).denial);
        assertTrue(command("policy", "capture", "clear").get(0).contains("deleted"));
        assertFalse(Files.exists(saved));
    }

    @Test void identityDependentEvaluationsAreSkippedAndRequireExplicitDiscard() throws Exception {
        command("policy", "capture", "start", "5m");
        evaluate(LIVE_UUID, true, false, false);
        ConnectionGuard.getRuleStore().add(AccessRule.Effect.DENY, AccessRule.Scope.ALL, "203.0.113.0/24", 0, "Local test rule");
        evaluate(null, false, false, false);
        List<String> status = command("policy", "capture", "status");
        assertEquals(1, ConnectionGuard.policyObservationCaptureStatus().skippedIdentity, status.toString());
        assertEquals(1, ConnectionGuard.policyObservationCaptureStatus().samples);
        assertTrue(command("policy", "capture", "discard").get(0).contains("discarded"));
        assertFalse(Files.exists(directory.resolve("policy/observations.json")));
    }

    @Test void captureWritesRequireSeparatePermissionAndRedactedReplayRejectsIdentityRules() throws Exception {
        List<String> denied = new ArrayList<>();
        OperationsCommands.handle(new String[]{"policy", "capture", "start"},
                permission -> permission.equals("connectionguard.command.policy"), denied::add);
        assertEquals(Collections.singletonList(ConnectionGuard.getMessages().getString("ops.permission")), denied);
        assertFalse(Files.exists(directory.resolve("policy")));

        command("policy", "capture", "start", "5m"); evaluate(null, false, false, false); command("policy", "capture", "stop");
        String identityCandidate = CANDIDATE.replace("\"rules\":[]", "\"rules\":[{\"id\":\"staff\",\"effect\":\"ALLOW\",\"scope\":\"ALL\",\"target\":\"203.0.113.1\",\"expires_at\":0,\"reason\":\"test\"}]");
        Files.write(directory.resolve("policy/candidate.json"), identityCandidate.getBytes(StandardCharsets.UTF_8));
        List<String> output = command("policy", "test", "observations", "candidate");
        assertEquals(1, output.size()); assertTrue(output.get(0).contains("rejected"));
        assertFalse(output.toString().contains(LIVE_IP));
    }

    @Test void savedDocumentsRejectRealIdentityFieldsOnlyInFactsMode() throws Exception {
        ConnectionGuard.startPolicyObservationCapture(300_000);
        evaluate(LIVE_UUID, true, false, false); ConnectionGuard.stopPolicyObservationCapture();
        JsonObject saved = PolicyJson.read(Files.newInputStream(directory.resolve("policy/observations.json")));
        for (String field : Arrays.asList("ip", "uuid")) {
            JsonObject changed = saved.deepCopy();
            changed.getAsJsonArray("cases").get(0).getAsJsonObject().addProperty(field, field.equals("ip") ? LIVE_IP : LIVE_UUID.toString());
            assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCases(new ByteArrayInputStream(changed.toString().getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test void explicitlySelectedIdentityModeReplaysLiteralRulesAndTrustedUuidWithoutActions() throws Exception {
        List<String> started = command("policy", "capture", "start", "5m", "identities");
        assertTrue(started.get(0).contains("IDENTITIES explicitly selected"));
        evaluate(LIVE_UUID, true, false, false); command("policy", "capture", "stop");
        Path saved = directory.resolve("policy/observations.json");
        String contents = new String(Files.readAllBytes(saved), StandardCharsets.UTF_8);
        assertTrue(contents.contains(LIVE_IP)); assertTrue(contents.contains(LIVE_UUID.toString()));
        assertFalse(contents.contains("PRIVATE-CITY-NAME"));
        PolicyReplay.Cases cases;
        try (InputStream input = Files.newInputStream(saved)) { cases = PolicyReplay.readCases(input); }
        assertTrue(cases.observed); assertFalse(cases.identitiesRedacted);
        AccessRule rule = new AccessRule("verified", AccessRule.Effect.ALLOW, AccessRule.Scope.VPN, LIVE_UUID.toString(), 0, "Local test");
        Map<String,Object> settings = new HashMap<>(); settings.put("behavior.vpn.kick-player", true);
        PolicyReplay.Snapshot candidate = new PolicyReplay.Snapshot(GuardSettings.read(settings::get, Collections.emptyList()), Collections.singletonList(rule));
        assertTrue(cases.cases.get(0).evaluate(candidate, cases.cases.get(0).observedAt).vpnRule.isBypassed());
        assertNull(cases.cases.get(0).evaluate(candidate, cases.cases.get(0).observedAt).denial);
        Files.write(directory.resolve("policy/candidate.json"), candidate.toJson().toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(command("policy", "test", "observations", "candidate", "captured").get(0).contains("Saved local observations: 1"));
        assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
        ConnectionGuard.shutdown(); ConnectionGuard.applySettings(GuardSettings.defaults()); ConnectionGuard.initializeRules(directory);
        assertTrue(command("policy", "capture", "status").toString().contains("savedIdentityMode=IDENTITIES"));
        assertTrue(command("policy", "test", "observations", "candidate", "captured").get(0).contains("Saved local observations: 1"));
    }
}
