package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class ResilientCacheTest {
    @Test void unavailableRedisDoesNotBlockReadinessOrMemoryCache() throws Exception {
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int port = unused.getLocalPort(); unused.close();
            ResilientRedisCacheProvider cache = new ResilientRedisCacheProvider(new RedisCacheProvider("127.0.0.1", port, "", ""));
            try {
                cache.setNamespace("startup-test"); assertTrue(cache.setup().get(100, TimeUnit.MILLISECONDS));
                cache.addVpnResult(new VpnResult("192.0.2.7", true)).get();
                assertTrue(cache.getVpnResult("192.0.2.7").get(100, TimeUnit.MILLISECONDS).get().isVpn());
                assertTrue(cache.describe().contains("fallback=Memory"));
            } finally { cache.disband().get(3, TimeUnit.SECONDS); }
        }
    }
    @Test void reconnectsAndAnOutageClearCannotResurrectAStaleAllow() throws Exception {
        AtomicBoolean online = new AtomicBoolean(); AtomicInteger attempts = new AtomicInteger(), clears = new AtomicInteger();
        MemoryCacheProvider data = new MemoryCacheProvider(); data.setNamespace("recovery-test");
        data.addVpnResult(new VpnResult("192.0.2.7", false)).get();
        NoCacheProvider redis = new NoCacheProvider() {
            @Override public CompletableFuture<Boolean> setup() { attempts.incrementAndGet(); return CompletableFuture.completedFuture(online.get()); }
            @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) { return data.getVpnResult(ip); }
            @Override public CompletableFuture<Boolean> removeAllVpnResults() { clears.incrementAndGet(); return data.removeAllVpnResults(); }
        };
        ResilientRedisCacheProvider cache = new ResilientRedisCacheProvider(redis);
        try {
            cache.setNamespace("recovery-test"); assertTrue(cache.setup().get()); cache.removeVpnResult("192.0.2.7").get();
            online.set(true); long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!cache.describe().contains("Redis=connected") && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(cache.describe().contains("Redis=connected")); assertTrue(attempts.get() > 0); assertEquals(1, clears.get());
            assertFalse(cache.getVpnResult("192.0.2.7").get().isPresent());
        } finally { cache.disband().get(); }
    }
    @Test void memoryCopiesResultsAndInvalidatesConfigurationNamespaces() throws Exception {
        MemoryCacheProvider cache = new MemoryCacheProvider(); cache.setNamespace("a");
        cache.addVpnResult(new VpnResult("192.0.2.7", true)).get(); VpnResult copy = cache.getVpnResult("192.0.2.7").get().get(); copy.setVpn(false);
        assertTrue(cache.getVpnResult("192.0.2.7").get().get().isVpn());
        cache.setNamespace("b"); assertFalse(cache.getVpnResult("192.0.2.7").get().isPresent());
        cache.disband(); assertFalse(cache.setup().get());
    }
}
