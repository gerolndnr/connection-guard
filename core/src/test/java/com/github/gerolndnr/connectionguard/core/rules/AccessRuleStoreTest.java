package com.github.gerolndnr.connectionguard.core.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule.*;

class AccessRuleStoreTest {
    @TempDir Path directory;
    @Test void ipv4Ipv6AndMappedCidrMatchCorrectBoundaries() {
        IpNetwork v4 = IpNetwork.parse("192.0.2.19/24");
        assertTrue(v4.contains("192.0.2.255")); assertTrue(v4.contains("::ffff:192.0.2.1"));
        assertFalse(v4.contains("192.0.3.0")); assertFalse(v4.contains("2001:db8::1"));
        IpNetwork v6 = IpNetwork.parse("2001:db8::/32");
        assertTrue(v6.contains("2001:0db8:1234::1")); assertFalse(v6.contains("2001:db9::1"));
        assertEquals(v4.toString(), IpNetwork.parse("::ffff:192.0.2.0/120").toString());
        assertThrows(IllegalArgumentException.class, () -> IpNetwork.parse("::ffff:192.0.2.0/64"));
        for (String invalid : new String[]{"example.com/24", "192.0.2.0/33", "::/129", "1.2.3.4/-1", "1.2.3.4/x"}) {
            assertThrows(IllegalArgumentException.class, () -> IpNetwork.parse(invalid));
        }
    }
    @Test void explicitDenyBeatsAllowAndScopesRemainSeparate() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        store.add(Effect.ALLOW, Scope.ALL, "192.0.2.1", 0, "Fixture allow");
        AccessRule denied = store.add(Effect.DENY, Scope.VPN, "192.0.2.0/24", 0, "Fixture deny");
        assertEquals(denied.getId(), store.match("192.0.2.1", null, false, Scope.VPN).get().getId());
        assertEquals(Effect.ALLOW, store.match("192.0.2.1", null, false, Scope.GEO).get().getEffect());
        assertTrue(store.remove(denied.getId()));
        assertEquals(Effect.ALLOW, store.match("192.0.2.1", null, false, Scope.VPN).get().getEffect());
    }
    @Test void uuidMustBeTrustedAndExpiredExemptionsNeverMatch() throws Exception {
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        AccessRuleStore store = new AccessRuleStore(directory);
        store.add(Effect.EXEMPT, Scope.VPN, uuid.toString(), System.currentTimeMillis()+60000, "Temporary fixture");
        assertFalse(store.match("192.0.2.1", uuid, false, Scope.VPN).isPresent());
        assertTrue(store.match("192.0.2.1", uuid, true, Scope.VPN).isPresent());
        assertFalse(store.match("192.0.2.1", uuid, true, Scope.GEO).isPresent());
        assertFalse(new AccessRule("expired", Effect.EXEMPT, Scope.ALL, uuid.toString(), 1, "Expired fixture")
                .matches("192.0.2.1", uuid, true, Scope.VPN, System.currentTimeMillis()));
    }
    @Test void roundtripPreservesReasonScopeAndExpiryAndBrokenReloadPreservesActiveRules() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        AccessRule added = store.add(Effect.DENY, Scope.ALL, "2001:db8::/32", System.currentTimeMillis()+60000, "Fixture reason");
        AccessRuleStore reloaded = new AccessRuleStore(directory);
        AccessRule stored = reloaded.snapshot().get(0);
        assertEquals(added.getExpiresAt(), stored.getExpiresAt()); assertEquals(added.getReason(), stored.getReason());
        Files.write(directory.resolve("access-rules.json"), "not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, reloaded::reload);
        assertEquals(added.getId(), reloaded.snapshot().get(0).getId());
    }
    @Test void namesAndControlCharactersCannotBecomeRules() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        assertThrows(IllegalArgumentException.class, () -> store.add(Effect.EXEMPT, Scope.ALL, "Admin", 0, "Reason"));
        assertThrows(IllegalArgumentException.class, () -> store.add(Effect.DENY, Scope.ALL, "192.0.2.1", 0, "Reason\nforged line"));
        assertTrue(store.snapshot().isEmpty());
    }
    @Test void backgroundPrunePersistsOnlyExpiredRulesAndLeavesFutureAndPermanentRules() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        long now = System.currentTimeMillis();
        store.add(Effect.ALLOW, Scope.VPN, "192.0.2.1", now, "Expired fixture");
        AccessRule permanent = store.add(Effect.DENY, Scope.ALL, "192.0.2.2", 0, "Permanent fixture");
        AccessRule future = store.add(Effect.EXEMPT, Scope.GEO, "192.0.2.3", now + 60000, "Future fixture");
        assertEquals(1, store.pruneExpired(now));
        AccessRuleStore reloaded = new AccessRuleStore(directory);
        assertEquals(2, reloaded.snapshot().size());
        assertEquals(permanent.getId(), reloaded.snapshot().get(0).getId());
        assertEquals(future.getId(), reloaded.snapshot().get(1).getId());
        byte[] unchanged = Files.readAllBytes(directory.resolve("access-rules.json"));
        assertEquals(0, store.pruneExpired(now));
        assertArrayEquals(unchanged, Files.readAllBytes(directory.resolve("access-rules.json")));
    }

}
