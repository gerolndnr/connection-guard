package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Memory is ready immediately. Redis is probed on a dedicated daemon, never the startup thread. */
public final class ResilientRedisCacheProvider implements CacheProvider {
    private final CacheProvider remote;
    private final MemoryCacheProvider memory = new MemoryCacheProvider();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "cg-redis-reconnect"); t.setDaemon(true); return t;
    });
    private volatile boolean available, closed;
    private boolean started, warned, dirty;
    private long generation;
    private String namespace;
    public ResilientRedisCacheProvider(CacheProvider remote) { this.remote = Objects.requireNonNull(remote); }
    @Override public synchronized void setNamespace(String next) {
        memory.setNamespace(next); remote.setNamespace(next);
        if (!Objects.equals(namespace, next)) { namespace = next; available = false; generation++; }
    }
    @Override public synchronized CompletableFuture<Boolean> setup() {
        if (closed) return CompletableFuture.completedFuture(false);
        if (!started) { started = true; retries.scheduleWithFixedDelay(this::reconnect, 0, 2, TimeUnit.SECONDS); }
        return memory.setup();
    }
    private void reconnect() {
        final long expected; final boolean clear;
        synchronized (this) { if (closed) return; expected = generation; clear = dirty; }
        try {
            if (!Boolean.TRUE.equals(remote.setup().get(1500, TimeUnit.MILLISECONDS))) throw new IllegalStateException();
            // Blocking remote work never owns the lock used by invalidation/reload/shutdown.
            if (clear && (!Boolean.TRUE.equals(remote.removeAllVpnResults().get(1500, TimeUnit.MILLISECONDS))
                    || !Boolean.TRUE.equals(remote.removeAllGeoResults().get(1500, TimeUnit.MILLISECONDS)))) throw new IllegalStateException();
            synchronized (this) {
                if (closed || generation != expected) return;
                dirty = false;
                boolean recovered = !available; available = true;
                if (recovered && warned && ConnectionGuard.getLogger() != null) ConnectionGuard.getLogger().info("Redis reconnected; memory cache remains available.");
                warned = false;
            }
        } catch (Exception unavailable) { failed(); }
    }
    private synchronized void failed() {
        available = false;
        if (!warned && !closed && ConnectionGuard.getLogger() != null) ConnectionGuard.getLogger().warning("Redis unavailable: using bounded Memory cache; reconnecting in background. Connection checks remain active (no credentials logged).");
        warned = true;
    }
    public String describe() { return "Redis=" + (available ? "connected" : "unavailable") + " fallback=Memory reconnectMs=2000; no persistence while Redis is unavailable"; }
    private <T> CompletableFuture<Optional<T>> read(Supplier<CompletableFuture<Optional<T>>> local, Supplier<CompletableFuture<Optional<T>>> redis) {
        return local.get().thenCompose(cached -> {
            if (cached.isPresent() || !available || closed) return CompletableFuture.completedFuture(cached);
            final long expected; synchronized (this) { expected = generation; }
            try { return redis.get().handle((answer, error) -> {
                if (error != null) { failed(); return Optional.<T>empty(); }
                synchronized (this) { return closed || generation != expected || answer == null ? Optional.<T>empty() : answer; }
            }); }
            catch (RuntimeException unavailable) { failed(); return CompletableFuture.completedFuture(Optional.empty()); }
        });
    }
    private void mirror(Supplier<? extends CompletableFuture<?>> operation) {
        if (!available || closed) return;
        try { operation.get().exceptionally(error -> { failed(); return null; }); }
        catch (RuntimeException unavailable) { failed(); }
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) { return read(() -> memory.getVpnResult(ip), () -> remote.getVpnResult(ip)); }
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) { return read(() -> memory.getGeoResult(ip), () -> remote.getGeoResult(ip)); }
    @Override public CompletableFuture<Void> addVpnResult(VpnResult value) { CompletableFuture<Void> ready = memory.addVpnResult(value); mirror(() -> remote.addVpnResult(value)); return ready; }
    @Override public CompletableFuture<Void> addGeoResult(GeoResult value) { CompletableFuture<Void> ready = memory.addGeoResult(value); mirror(() -> remote.addGeoResult(value)); return ready; }
    private synchronized CompletableFuture<Boolean> invalidate(Supplier<CompletableFuture<Boolean>> local) {
        dirty = true; available = false; generation++; return local.get();
    }
    @Override public CompletableFuture<Boolean> removeVpnResult(String ip) { return invalidate(() -> memory.removeVpnResult(ip)); }
    @Override public CompletableFuture<Boolean> removeGeoResult(String ip) { return invalidate(() -> memory.removeGeoResult(ip)); }
    @Override public CompletableFuture<Boolean> removeAllVpnResults() { return invalidate(memory::removeAllVpnResults); }
    @Override public CompletableFuture<Boolean> removeAllGeoResults() { return invalidate(memory::removeAllGeoResults); }
    @Override public synchronized CompletableFuture<Boolean> disband() {
        closed = true; available = false; retries.shutdownNow(); memory.disband(); return remote.disband();
    }
}
