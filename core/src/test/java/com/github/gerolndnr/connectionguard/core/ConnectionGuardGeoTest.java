package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ConnectionGuardGeoTest {
    static class Cache extends NoCacheProvider {
        Optional<GeoResult> result = Optional.empty();
        @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) { return CompletableFuture.completedFuture(result); }
        @Override public CompletableFuture<Void> addGeoResult(GeoResult value) { result = Optional.of(value); return CompletableFuture.completedFuture(null); }
    }
    @Test void cachesSuccessfulGeoResults() throws Exception {
        Cache cache = new Cache();
        AtomicInteger calls = new AtomicInteger();
        ConnectionGuard.setCacheProvider(cache);
        ConnectionGuard.setGeoProvider(ip -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new GeoResult(ip, "ZZ", "Test", "Test ISP"))); });
        assertTrue(ConnectionGuard.getGeoResult("192.0.2.1").get(2, TimeUnit.SECONDS).isPresent());
        assertTrue(ConnectionGuard.getGeoResult("192.0.2.1").get(2, TimeUnit.SECONDS).isPresent());
        assertEquals(1, calls.get());
    }
    @Test void providerFailureIsNotCachedAndCanRecover() throws Exception {
        Cache cache = new Cache();
        AtomicInteger calls = new AtomicInteger();
        ConnectionGuard.setCacheProvider(cache);
        ConnectionGuard.setGeoProvider(ip -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("private key must not be logged");
            return CompletableFuture.completedFuture(Optional.of(new GeoResult(ip, "ZZ", "Test", "Test ISP")));
        });
        assertFalse(ConnectionGuard.getGeoResult("192.0.2.1").get(2, TimeUnit.SECONDS).isPresent());
        assertFalse(cache.result.isPresent());
        assertTrue(ConnectionGuard.getGeoResult("192.0.2.1").get(2, TimeUnit.SECONDS).isPresent());
    }
    @Test void asynchronousFailureIsUnavailable() throws Exception {
        ConnectionGuard.setCacheProvider(new Cache());
        ConnectionGuard.setGeoProvider(ip -> { CompletableFuture<Optional<GeoResult>> failed = new CompletableFuture<>(); failed.completeExceptionally(new IllegalStateException("sensitive")); return failed; });
        assertFalse(ConnectionGuard.getGeoResult("192.0.2.1").get(2, TimeUnit.SECONDS).isPresent());
    }
}
