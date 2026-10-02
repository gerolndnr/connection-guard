package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.lookup.LookupSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class RulesCommandsTest {
    @TempDir Path directory;
    @BeforeEach void setup() { ConnectionGuard.configureLookup(LookupSettings.defaults()); ConnectionGuard.initializeRules(directory); }
    @Test void deniedPermissionCannotWriteOrReadRules() {
        List<String> messages = new ArrayList<>();
        assertTrue(RulesCommands.handle(new String[]{"deny", "add", "192.0.2.1", "all", "1h", "Fixture"}, permission -> false, messages::add));
        assertEquals(1, messages.size()); assertTrue(messages.get(0).contains("permission"));
        assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
        assertFalse(java.nio.file.Files.exists(directory.resolve("access-rules.json")));
    }
    @Test void oneWordReasonIsAcceptedAndPersistedAsynchronously() throws Exception {
        CountDownLatch reply = new CountDownLatch(1);
        RulesCommands.handle(new String[]{"allow", "add", "192.0.2.1", "vpn", "15m", "Fixture"}, permission -> true, text -> reply.countDown());
        assertTrue(reply.await(2, TimeUnit.SECONDS));
        assertEquals(1, ConnectionGuard.getRuleStore().snapshot().size());
        assertEquals("Fixture", ConnectionGuard.getRuleStore().snapshot().get(0).getReason());
        assertTrue(ConnectionGuard.getRuleStore().snapshot().get(0).getExpiresAt() > System.currentTimeMillis());
    }
    @Test void invalidTargetsAndDurationsDoNotCreateFiles() {
        List<String> replies = new ArrayList<>();
        RulesCommands.handle(new String[]{"exempt", "add", "Admin", "all", "1h", "Fixture"}, permission -> true, replies::add);
        RulesCommands.handle(new String[]{"exempt", "add", "192.0.2.1", "all", "999999d", "Fixture"}, permission -> true, replies::add);
        assertEquals(2, replies.size()); assertTrue(ConnectionGuard.getRuleStore().snapshot().isEmpty());
        assertFalse(java.nio.file.Files.exists(directory.resolve("access-rules.json")));
    }
    @Test void multiwordOperatorTargetIsNotConfusedWithScopeOrReason() throws Exception {
        CountDownLatch reply = new CountDownLatch(1);
        RulesCommands.handle(new String[]{"deny", "add", "operator:Fixture", "VPN", "Company", "vpn", "15m", "Synthetic", "rule"}, permission -> true, text -> reply.countDown());
        assertTrue(reply.await(2, TimeUnit.SECONDS));
        assertEquals("operator:Fixture VPN Company", ConnectionGuard.getRuleStore().snapshot().get(0).getTarget());
        assertEquals("Synthetic rule", ConnectionGuard.getRuleStore().snapshot().get(0).getReason());
    }
}
