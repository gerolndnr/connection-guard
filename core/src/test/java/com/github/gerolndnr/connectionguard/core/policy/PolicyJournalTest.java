package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class PolicyJournalTest {
    private PolicyReplay.Snapshot policy() {
        AccessRule rule = new AccessRule("temporary", AccessRule.Effect.ALLOW, AccessRule.Scope.ALL, "2001:db8::/32", 12345, "Synthetic grant");
        return new PolicyReplay.Snapshot(GuardSettings.defaults(), Collections.singletonList(rule));
    }
    private PolicyJournal journal() {
        return PolicyJournal.baseline(GuardSettings.defaults(), Collections.emptyList(), 1)
                .append(policy(), PolicyJournal.Operation.ACTIVATE, true, GuardSettings.defaults(), 2);
    }
    private PolicyJournal read(byte[] bytes) throws IOException { return PolicyJournal.read(new ByteArrayInputStream(bytes)); }
    private void rejected(Consumer<JsonObject> mutation) throws Exception {
        JsonObject document = JsonParser.parseString(new String(journal().bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        mutation.accept(document);
        assertThrows(Exception.class, () -> read(document.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void restartPreservesExactRevisionExpiryAndBoundedImmutableHistory() throws Exception {
        PolicyJournal history = journal();
        for (int n = 3; n <= 20; n++) history = history.append(policy(), PolicyJournal.Operation.ROLLBACK, true, GuardSettings.defaults(), n);
        PolicyJournal restored = read(history.bytes());
        assertEquals(3, restored.revisions.size()); assertEquals(history.current().id, restored.current().id);
        assertEquals(history.current().policy.fingerprint(), restored.current().policy.fingerprint());
        assertEquals(12345, restored.current().policy.rules.get(0).getExpiresAt());
        assertThrows(UnsupportedOperationException.class, () -> restored.revisions.clear());
        assertThrows(UnsupportedOperationException.class, () -> restored.current().policy.rules.clear());
        assertThrows(IllegalArgumentException.class, () -> restored.find("missing"));
    }
    @Test void malformedSchemaOwnershipIdsAndOrderCannotBecomeActiveState() throws Exception {
        rejected(j -> j.addProperty("schema", 2)); rejected(j -> j.addProperty("schema", "1"));
        rejected(j -> j.addProperty("local", "true")); rejected(j -> j.addProperty("local", false));
        rejected(j -> j.addProperty("provider_secret", "PRIVATE"));
        rejected(j -> j.add("revisions", new JsonArray()));
        rejected(j -> j.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("id", "p1-forged"));
        rejected(j -> j.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("number", 0));
        rejected(j -> j.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("created_at", -1));
        rejected(j -> j.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("operation", "EXECUTE"));
        rejected(j -> j.getAsJsonObject("config_base").add("rules", j.getAsJsonArray("revisions").get(0).getAsJsonObject().getAsJsonObject("policy").get("rules")));
    }
    @Test void duplicateKeysInvalidUtf8OversizeAndTooManyRulesAreRejected() throws Exception {
        String valid = new String(journal().bytes(), StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> read(valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1").getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> read(new byte[]{(byte)0xc0, (byte)0xaf}));
        assertThrows(IllegalArgumentException.class, () -> read(new byte[PolicyJournal.MAX_BYTES + 1]));
        List<AccessRule> rules = new ArrayList<>();
        for (int n = 0; n < 513; n++) rules.add(new AccessRule("r" + n, AccessRule.Effect.DENY, AccessRule.Scope.VPN, "192.0.2.1", 0, "Fixture"));
        assertThrows(IllegalArgumentException.class, () -> PolicyJournal.baseline(GuardSettings.defaults(), rules, 1));
    }
}
