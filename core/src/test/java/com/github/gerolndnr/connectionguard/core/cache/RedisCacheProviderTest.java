package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
@EnabledIfEnvironmentVariable(named="CG_TEST_REDIS", matches="[0-9]+")
class RedisCacheProviderTest {
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
