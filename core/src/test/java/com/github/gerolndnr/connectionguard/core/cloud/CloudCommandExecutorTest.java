package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore;
import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CloudCommandExecutorTest {
    @TempDir Path dir;
    @BeforeEach void setup() throws Exception { ConnectionGuard.initializeRules(dir); }
    private JsonObject command() {
        JsonObject c = new JsonObject();
        c.addProperty("id", "cmd_AAAAAAAAAAAAAAAA"); c.addProperty("type", "access_rule.add");
        c.addProperty("effect", "ALLOW"); c.addProperty("scope", "VPN"); c.addProperty("target", "192.0.2.12");
        c.add("note", JsonNull.INSTANCE); return c;
    }
    @Test void absoluteDeadlinePersistsAndStopsMatchingAtExactBoundary() throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        JsonObject c = command(); c.addProperty("expires_at", deadline);
        CloudCommandExecutor.execute("access_rule.add", c);
        AccessRule stored = new AccessRuleStore(dir).snapshot().get(0);
        assertEquals(deadline, stored.getExpiresAt()); assertEquals(AccessRule.Scope.VPN, stored.getScope());
        assertTrue(stored.matches("192.0.2.12", null, false, AccessRule.Scope.VPN, deadline - 1));
        assertFalse(stored.matches("192.0.2.12", null, false, AccessRule.Scope.VPN, deadline));
        assertFalse(stored.matches("192.0.2.12", null, false, AccessRule.Scope.GEO, deadline - 1));
    }
    @Test void missingAndNullExpiryKeepExplicitLegacyPermanentOperatorRules() throws Exception {
        JsonObject absent = command(); CloudCommandExecutor.execute("access_rule.add", absent);
        JsonObject nil = command(); nil.add("expires_at", JsonNull.INSTANCE); nil.addProperty("note", "");
        CloudCommandExecutor.execute("access_rule.add", nil);
        assertEquals(2, ConnectionGuard.getRuleStore().snapshot().size());
        for (AccessRule rule : ConnectionGuard.getRuleStore().snapshot()) assertEquals(0, rule.getExpiresAt());
    }
    @Test void malformedRoundedAndOverflowingDeadlinesCannotCreateAnyRule() throws Exception {
        String[] values = {"0", "-1",
            "\"1800000000000\"", "true", "[]", "{}", "1800000000000.1", "9007199254740992", "9223372036854775808", "1e100"};
        for (String value : values) {
            JsonObject c = command(); c.add("expires_at", JsonParser.parseString(value));
            assertThrows(IllegalArgumentException.class, () -> CloudCommandExecutor.execute("access_rule.add", c), value);
            assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
            assertFalse(Files.exists(dir.resolve("access-rules.json")));
        }
    }
    @Test void delayedAlreadyExpiredCommandAcknowledgesWithoutAStoredGrant() throws Exception {
        JsonObject c = command(); c.addProperty("expires_at", System.currentTimeMillis() - 1000);
        assertEquals("Already expired", CloudCommandExecutor.execute("access_rule.add", c));
        assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
        assertFalse(Files.exists(dir.resolve("access-rules.json")));
    }
    @Test void unknownKeysWrongEnvelopeOrCoercedTextNeverMutateTheStore() throws Exception {
        JsonObject extra = command(); extra.addProperty("execute_command", "op synthetic");
        assertThrows(IllegalArgumentException.class, () -> CloudCommandExecutor.execute("access_rule.add", extra));
        JsonObject wrong = command(); wrong.addProperty("type", "cache.clear");
        assertThrows(IllegalArgumentException.class, () -> CloudCommandExecutor.execute("access_rule.add", wrong));
        JsonObject target = command(); target.addProperty("target", 123);
        assertThrows(IllegalArgumentException.class, () -> CloudCommandExecutor.execute("access_rule.add", target));
        JsonObject note = command(); note.add("note", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> CloudCommandExecutor.execute("access_rule.add", note));
        assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
    }
}
