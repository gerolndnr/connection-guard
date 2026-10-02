package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.LookupException;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
@EnabledIfEnvironmentVariable(named="CG_TEST_REDIS", matches="[0-9]+")
class RedisCacheProviderTest {
    private int port() { return Integer.parseInt(System.getenv("CG_TEST_REDIS")); }
    @Test void identicalDetectionNamespacesShareFactsAcrossIndependentClients() throws Exception {
        String namespace = "shared-" + UUID.randomUUID();
        RedisCacheProvider one = new RedisCacheProvider("127.0.0.1", port(), null, null);
        RedisCacheProvider two = new RedisCacheProvider("127.0.0.1", port(), null, null);
        one.setNamespace(namespace); two.setNamespace(namespace);
        try {
            assertTrue(one.setup().get()); assertTrue(two.setup().get());
            one.addVpnResult(new VpnResult("2001:db8::1", true, Optional.of("Synthetic provider"))).get();
            one.addGeoResult(new GeoResult("2001:db8::1", "JP", "Synthetic city", "Synthetic ISP")).get();
            assertTrue(two.getVpnResult("2001:db8::1").get().get().isVpn());
            assertEquals("JP", two.getGeoResult("2001:db8::1").get().get().getCountryName());
            assertTrue(two.removeAllGeoResults().get());
            assertFalse(one.getGeoResult("2001:db8::1").get().isPresent());
            assertTrue(one.getVpnResult("2001:db8::1").get().isPresent());
        } finally { one.removeAllVpnResults().get(); one.removeAllGeoResults().get(); one.disband().get(); two.disband().get(); }
    }
    @Test void queuedOperationsRetainNamespaceAndWriteTtlAcrossConfigurationChange() throws Exception {
        String namespace = "queued-" + UUID.randomUUID();
        RedisCacheProvider cache = new RedisCacheProvider("127.0.0.1", port(), null, null);
        cache.setNamespace(namespace);
        try (Jedis server = new Jedis("127.0.0.1", port())) {
            assertTrue(cache.setup().get()); ConnectionGuard.setVpnCacheExpirationTime(1);
            // Pause only this dedicated test service to queue a real write before changing configuration.
            server.clientPause(100);
            java.util.concurrent.CompletableFuture<Void> write = cache.addVpnResult(new VpnResult("192.0.2.2", true));
            cache.setNamespace(namespace + "-changed"); ConnectionGuard.setVpnCacheExpirationTime(2);
            write.get();
            assertNotNull(server.get("cg:v2:" + namespace + ":vpn:192.0.2.2"));
            assertNull(server.get("cg:v2:" + namespace + "-changed:vpn:192.0.2.2"));
            long ttl = server.ttl("cg:v2:" + namespace + ":vpn:192.0.2.2");
            assertTrue(ttl > 0 && ttl <= 60);
            assertFalse(cache.getVpnResult("192.0.2.2").get().isPresent());
        } finally { cache.setNamespace(namespace); cache.removeAllVpnResults().get(); cache.disband().get(); ConnectionGuard.setVpnCacheExpirationTime(1440); }
    }
    @Test void staleSourceAndMalformedPayloadRemainCacheMissesWithLiveRedisTtl() throws Exception {
        String namespace = "provenance-" + UUID.randomUUID();
        RedisCacheProvider cache = new RedisCacheProvider("127.0.0.1", port(), null, null); cache.setNamespace(namespace);
        String key = "cg:v2:" + namespace + ":vpn:192.0.2.3";
        try (Jedis server = new Jedis("127.0.0.1", port())) {
            assertTrue(cache.setup().get());
            VpnResult expired = new VpnResult("192.0.2.3", true);
            expired.setValidUntil(System.currentTimeMillis() - 1);
            cache.addVpnResult(expired).get();
            assertTrue(server.ttl(key) > 0); assertFalse(cache.getVpnResult("192.0.2.3").get().isPresent());
            server.setex(key, 60, "{invalid-json");
            assertFalse(cache.getVpnResult("192.0.2.3").get().isPresent());
            VpnResult valid = new VpnResult("192.0.2.3", true);
            cache.addVpnResult(valid).get(); assertTrue(cache.getVpnResult("192.0.2.3").get().get().isVpn());
        } finally { cache.removeAllVpnResults().get(); cache.disband().get(); }
    }
    @Test void explicitAclUserWorksAndInvalidCredentialErrorsAreRedacted() throws Exception {
        String namespace = "acl-" + UUID.randomUUID(), user = "cg-test-" + UUID.randomUUID();
        String secret = UUID.randomUUID().toString();
        RedisCacheProvider valid = new RedisCacheProvider("127.0.0.1", port(), user, secret);
        RedisCacheProvider invalid = new RedisCacheProvider("127.0.0.1", port(), user, "incorrect-" + secret);
        valid.setNamespace(namespace); invalid.setNamespace(namespace);
        try (Jedis admin = new Jedis("127.0.0.1", port())) {
            assertEquals("OK", admin.aclSetUser(user, "on", ">" + secret, "~cg:v2:" + namespace + ":*",
                    "+ping", "+get", "+setex", "+del", "+scan"));
            try {
                assertTrue(valid.setup().get());
                valid.addVpnResult(new VpnResult("192.0.2.4", true)).get();
                assertTrue(valid.getVpnResult("192.0.2.4").get().isPresent());
                java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> invalid.setup().get());
                assertInstanceOf(LookupException.class, failure.getCause());
                assertEquals(FailureReason.CACHE_ERROR, LookupException.reason(failure));
                assertFalse(failure.toString().contains(secret)); assertFalse(failure.toString().contains(user));
                assertTrue(valid.removeAllVpnResults().get());
            } finally { valid.disband().get(); invalid.disband().get(); admin.aclDelUser(user); }
        }
    }
    @Test void realRedisRoundtripTtlNamespaceIsolationAndScopedClear() throws Exception {
        int port = Integer.parseInt(System.getenv("CG_TEST_REDIS"));
        String namespace = "test-" + UUID.randomUUID();
        RedisCacheProvider one = new RedisCacheProvider("127.0.0.1", port, null, null);
        RedisCacheProvider two = new RedisCacheProvider("127.0.0.1", port, null, null);
        one.setNamespace(namespace); two.setNamespace(namespace + "-other");
        try {
            assertTrue(one.setup().get()); assertTrue(two.setup().get());
            ConnectionGuard.setVpnCacheExpirationTime(1);
            one.addVpnResult(new VpnResult("192.0.2.1", true, Optional.of("Fixture VPN"))).get();
            assertEquals("Fixture VPN", one.getVpnResult("192.0.2.1").get().get().getVpnProviderName().get());
            assertFalse(two.getVpnResult("192.0.2.1").get().isPresent());
            two.addVpnResult(new VpnResult("192.0.2.1", false)).get();
            try (Jedis server = new Jedis("127.0.0.1", port)) {
                long ttl = server.ttl("cg:v2:" + namespace + ":vpn:192.0.2.1");
                assertTrue(ttl > 0 && ttl <= 60);
                server.pexpire("cg:v2:" + namespace + ":vpn:192.0.2.1", 1);
                Thread.sleep(20);
                assertFalse(one.getVpnResult("192.0.2.1").get().isPresent());
            }
            assertTrue(one.removeAllVpnResults().get()); assertTrue(two.getVpnResult("192.0.2.1").get().isPresent());
        } finally { one.removeAllVpnResults().get(); two.removeAllVpnResults().get(); one.disband().get(); two.disband().get(); ConnectionGuard.setVpnCacheExpirationTime(1440); }
    }
}
