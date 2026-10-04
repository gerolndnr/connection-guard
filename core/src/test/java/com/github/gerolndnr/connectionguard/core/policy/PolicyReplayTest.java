package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyReplayTest {
    @TempDir Path directory;
    private static final String CANDIDATE = "{\"schema\":1,\"mode\":\"ENFORCE\",\"vpn_failure\":\"CLOSED\",\"geo_failure\":\"OPEN\",\"kick_vpn\":true,\"kick_geo\":true,\"geo_type\":\"BLACKLIST\",\"countries\":[\"DE\"],\"rules\":[]}";
    private InputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private JsonObject examples() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/policy/examples.json")) { return PolicyJson.read(resource); }
    }
    @BeforeEach void setup() { ConnectionGuard.applySettings(GuardSettings.defaults()); ConnectionGuard.initializeRules(directory); }
    @AfterEach void cleanup() { ConnectionGuard.shutdown(); }
    @Test void bundledDatasetHasKnownSyntheticOutcomesAndNeverChangesTheActivePolicy() throws Exception {
        PolicyReplay.Cases cases = PolicyReplay.readCases(input(examples().toString()));
        assertEquals(8, cases.cases.size()); assertEquals(0, cases.capturedAt);
        PolicyReplay.Snapshot candidate = PolicyReplay.readCandidate(input(CANDIDATE));
        Map<String, Reason> expected = new HashMap<>();
        expected.put("negative", Reason.GEO_FLAG); expected.put("vpn_positive", Reason.VPN_FLAG);
        expected.put("provider_timeout", Reason.LOOKUP_UNAVAILABLE); expected.put("geo_timeout", null);
        expected.put("vpn_exempt", Reason.GEO_FLAG); expected.put("expired_source", Reason.LOOKUP_UNAVAILABLE);
        expected.put("quorum_two", Reason.LOOKUP_UNAVAILABLE); expected.put("ipv6_verified", Reason.GEO_FLAG);
        for (PolicyReplay.Case entry : cases.cases) assertEquals(expected.get(entry.id), entry.evaluate(candidate, 100).denial, entry.id);
        assertSame(ConnectionGuard.getSettings(), ConnectionGuard.policySnapshot().settings);
        assertFalse(ConnectionGuard.getSettings().kickVpn); assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
    }
    @Test void candidateIsACompletePolicyNotAMergeAndRuleSnapshotsAreImmutable() throws Exception {
        JsonObject draft = JsonParser.parseString(CANDIDATE).getAsJsonObject();
        JsonArray rules = new JsonArray(); rules.add(JsonParser.parseString("{\"id\":\"deny\",\"effect\":\"DENY\",\"scope\":\"ALL\",\"target\":\"192.0.2.0/24\",\"expires_at\":100,\"reason\":\"Synthetic\"}")); draft.add("rules", rules);
        PolicyReplay.Snapshot snapshot = PolicyReplay.readCandidate(input(draft.toString()));
        PolicyReplay.Case negative = PolicyReplay.readCases(input(examples().toString())).cases.get(0);
        assertEquals(Reason.ACCESS_RULE, negative.evaluate(snapshot, 99).denial);
        assertEquals(Reason.GEO_FLAG, negative.evaluate(snapshot, 100).denial);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rules.clear());
        draft.remove("mode"); assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCandidate(input(draft.toString())));
    }
    @Test void numbersBooleansEnumsAndDuplicateKeysAreNotCoerced() {
        for (String bad : Arrays.asList(CANDIDATE.replace("\"schema\":1", "\"schema\":\"1\""),
                CANDIDATE.replace("\"schema\":1", "\"schema\":1.1"), CANDIDATE.replace("\"kick_vpn\":true", "\"kick_vpn\":\"true\""),
                CANDIDATE.replace("\"mode\":\"ENFORCE\"", "\"mode\":\"ALL\""), CANDIDATE.replace("\"schema\":1", "\"schema\":1,\"schema\":1")))
            assertThrows(Exception.class, () -> PolicyReplay.readCandidate(input(bad)), bad);
    }
    @Test void secretsAndUnknownFuturePolicyFieldsCannotEnterCandidate() {
        assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCandidate(input(CANDIDATE.replace("\"rules\":[]", "\"rules\":[],\"api_key\":\"PRIVATE-SECRET\""))));
        assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCandidate(input(CANDIDATE.replace("\"rules\":[]", "\"rules\":[],\"required_positive_flags\":1"))));
    }
    @Test void nonSyntheticKindAndRepeatedCaseNamesAreRejected() throws Exception {
        JsonObject data = examples(); data.addProperty("kind", "observations");
        assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCases(input(data.toString())));
        data.addProperty("kind", "synthetic"); data.getAsJsonArray("cases").add(data.getAsJsonArray("cases").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCases(input(data.toString())));
    }
    @Test void contradictorySourceFactsAndUnsupportedEnumsAreRejected() throws Exception {
        for (String key : Arrays.asList("status", "reason")) {
            JsonObject data = examples(); JsonObject source = data.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("vpn").getAsJsonArray("votes").get(0).getAsJsonObject();
            source.addProperty(key, key.equals("status") ? "UNKNOWN" : "TIMEOUT");
            assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCases(input(data.toString())));
            source.addProperty(key, "UNSUPPORTED"); assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCases(input(data.toString())));
        }
    }
    @Test void unavailableGeoStillRejectsInvalidAgeExpiryAndSourceVersion() throws Exception {
        for (String key : Arrays.asList("cached_at", "valid_until", "source_version")) {
            JsonObject data = examples();
            JsonObject geo = data.getAsJsonArray("cases").get(3).getAsJsonObject().getAsJsonObject("geo");
            assertNotEquals("NONE", geo.get("reason").getAsString());
            geo.addProperty(key, "invalid");
            assertThrows(IllegalArgumentException.class, () -> PolicyReplay.readCases(input(data.toString())), key);
        }
    }
    @Test void malformedUtf8CannotBecomeReplacementText() {
        byte[] malformed = new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '(', '"', '}'};
        assertThrows(IOException.class, () -> PolicyJson.read(new ByteArrayInputStream(malformed)));
    }
    @Test void riskThresholdIsExactAndUnknownDoesNotBecomeZero() throws Exception {
        JsonObject data = examples(); JsonArray one = new JsonArray(); one.add(data.getAsJsonArray("cases").get(0)); data.add("cases", one);
        JsonObject details = one.get(0).getAsJsonObject().getAsJsonObject("vpn").getAsJsonArray("votes").get(0).getAsJsonObject().getAsJsonObject("details");
        details.addProperty("risk", new java.math.BigDecimal("79.9"));
        PolicyReplay.Snapshot snapshot = new PolicyReplay.Snapshot(GuardSettings.defaults(), Collections.singletonList(new AccessRule("risk", AccessRule.Effect.DENY, AccessRule.Scope.VPN, "risk:fixture.a:80", 0, "Synthetic")));
        assertNull(PolicyReplay.readCases(input(data.toString())).cases.get(0).evaluate(snapshot, 100).denial);
        details.addProperty("risk", 80); assertEquals(Reason.ACCESS_RULE, PolicyReplay.readCases(input(data.toString())).cases.get(0).evaluate(snapshot, 100).denial);
        details.remove("risk"); assertTrue(PolicyReplay.readCases(input(data.toString())).cases.get(0).evaluate(snapshot, 100).vpnRule.isUnresolved());
    }
    @Test void payloadDepthAndSizeAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> PolicyJson.read(input(new String(new char[262145]).replace('\0', ' '))));
        String nested = "0"; for (int i = 0; i < 18; i++) nested = "[" + nested + "]";
        final String tooDeep = "{\"value\":" + nested + "}";
        assertThrows(IllegalArgumentException.class, () -> PolicyJson.read(input(tooDeep)));
    }
    @Test void commandPermissionIsCheckedBeforeFileRead() {
        List<String> output = new ArrayList<>();
        assertTrue(OperationsCommands.handle(new String[]{"policy", "test", "../secret"}, permission -> false, output::add));
        assertEquals(1, output.size()); assertTrue(output.get(0).contains("permission")); assertFalse(Files.exists(directory.resolve("policy")));
    }
    @Test void defaultCommandIsOfflineAndLeavesSettingsRulesHealthAndFilesUnchanged() throws Exception {
        GuardSettings before = ConnectionGuard.getSettings(); String stats = ConnectionGuard.lookupStats();
        Map<String, ?> health = new HashMap<>(ConnectionGuard.providerHealth());
        List<String> output = new ArrayList<>();
        assertTrue(OperationsCommands.handle(new String[]{"policy", "test"}, permission -> permission.equals("connectionguard.command.policy"), output::add));
        assertEquals(11, output.size()); assertTrue(output.get(0).contains("changed outcomes: 0"));
        assertTrue(output.get(2).contains("no provider requests"));
        assertSame(before, ConnectionGuard.getSettings()); assertEquals(stats, ConnectionGuard.lookupStats()); assertEquals(health, ConnectionGuard.providerHealth());
        assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(0, files.count()); }
        assertFalse(output.toString().contains("00000000-0000")); assertFalse(output.toString().contains("192.0.2.10"));
    }
    @Test void commandComparesCandidateWithoutActivationAndDoesNotDisplaySecrets() throws Exception {
        Files.createDirectory(directory.resolve("policy")); Files.write(directory.resolve("policy/candidate.json"), CANDIDATE.getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(directory.resolve("policy/candidate.json"));
        List<String> output = new ArrayList<>();
        OperationsCommands.handle(new String[]{"policy", "test", "examples", "candidate"}, permission -> true, output::add);
        assertTrue(output.get(0).contains("changed outcomes: 7")); assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy/candidate.json")));
        assertFalse(ConnectionGuard.getSettings().kickVpn); assertFalse(Files.exists(directory.resolve("access-rules.json")));
        Files.write(directory.resolve("policy/candidate.json"), CANDIDATE.replace("\"mode\":\"ENFORCE\"", "\"mode\":\"PRIVATE-SECRET\"").getBytes(StandardCharsets.UTF_8));
        output.clear(); OperationsCommands.handle(new String[]{"policy", "test", "examples", "candidate"}, permission -> true, output::add);
        assertEquals(1, output.size()); assertTrue(output.get(0).contains("rejected")); assertFalse(output.get(0).contains("PRIVATE-SECRET"));
    }
    @Test void pathTraversalAndSymlinkedInputsAreRejected() throws Exception {
        Path policy = Files.createDirectory(directory.resolve("policy"));
        Path other = directory.resolve("other.json"); Files.write(other, examples().toString().getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(policy.resolve("linked.json"), other);
        for (String name : Arrays.asList("../other", "linked", "missing", "/absolute")) {
            List<String> output = new ArrayList<>(); OperationsCommands.handle(new String[]{"policy", "test", name}, p -> true, output::add);
            assertEquals(1, output.size()); assertTrue(output.get(0).contains("rejected"));
        }
    }
}
