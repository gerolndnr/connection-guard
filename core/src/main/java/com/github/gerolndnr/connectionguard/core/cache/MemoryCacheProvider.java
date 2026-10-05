package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded serialized copies, using the same TTL/provenance validation as persistent caches. */
public final class MemoryCacheProvider implements CacheProvider {
    private final LinkedHashMap<String, String> entries = new LinkedHashMap<String, String>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> oldest) { return size() > 10000; }
    };
    private String namespace;
    private boolean closed;
    @Override public synchronized void setNamespace(String next) {
        if (next == null || !next.matches("[a-zA-Z0-9:_-]{1,100}")) throw new IllegalArgumentException("Invalid cache namespace.");
        if (!Objects.equals(namespace, next)) { entries.clear(); namespace = next; }
    }
    @Override public CompletableFuture<Boolean> setup() { return CompletableFuture.completedFuture(!closed); }
    @Override public synchronized CompletableFuture<Boolean> disband() { closed = true; entries.clear(); return CompletableFuture.completedFuture(true); }
    @Override public synchronized CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return CompletableFuture.completedFuture(closed ? Optional.empty() : CacheCodec.vpn(entries.get("vpn:" + ip), ip, ConnectionGuard.getVpnCacheExpirationTime() * 60000L));
    }
    @Override public synchronized CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) {
        return CompletableFuture.completedFuture(closed ? Optional.empty() : CacheCodec.geo(entries.get("geo:" + ip), ip, ConnectionGuard.getGeoCacheExpirationTime() * 60000L));
    }
    @Override public synchronized CompletableFuture<Void> addVpnResult(VpnResult result) {
        if (!closed) { result.setCachedOn(System.currentTimeMillis()); entries.put("vpn:" + result.getIpAddress(), CacheCodec.encode(result)); }
        return CompletableFuture.completedFuture(null);
    }
    @Override public synchronized CompletableFuture<Void> addGeoResult(GeoResult result) {
        if (!closed) { result.setCachedOn(System.currentTimeMillis()); entries.put("geo:" + result.getIpAddress(), CacheCodec.encode(result)); }
        return CompletableFuture.completedFuture(null);
    }
    @Override public synchronized CompletableFuture<Boolean> removeVpnResult(String ip) { entries.remove("vpn:" + ip); return CompletableFuture.completedFuture(true); }
    @Override public synchronized CompletableFuture<Boolean> removeGeoResult(String ip) { entries.remove("geo:" + ip); return CompletableFuture.completedFuture(true); }
    private synchronized CompletableFuture<Boolean> clear(String prefix) { entries.keySet().removeIf(key -> key.startsWith(prefix)); return CompletableFuture.completedFuture(true); }
    @Override public CompletableFuture<Boolean> removeAllVpnResults() { return clear("vpn:"); }
    @Override public CompletableFuture<Boolean> removeAllGeoResults() { return clear("geo:"); }
}
