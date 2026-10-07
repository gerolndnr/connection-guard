package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded memory facts first; persistent misses only. Epochs fence late reads after edits. */
public final class TieredCacheProvider implements CacheProvider {
    private final MemoryCacheProvider memory=new MemoryCacheProvider();
    private final CacheProvider persistent;
    private long epoch;
    private boolean closed, invalidationFailed;
    private int invalidations;
    public TieredCacheProvider(CacheProvider persistent) { this.persistent=Objects.requireNonNull(persistent); }
    @Override public synchronized void setNamespace(String namespace) {
        epoch++;memory.setNamespace(namespace);persistent.setNamespace(namespace);
    }
    @Override public CompletableFuture<Boolean> setup() { memory.setup();return persistent.setup(); }
    private synchronized <T> CompletableFuture<Optional<T>> read(Supplier<CompletableFuture<Optional<T>>> local,
            Supplier<CompletableFuture<Optional<T>>> remote,java.util.function.Consumer<T> remember) {
        Optional<T> cached=local.get().getNow(Optional.empty());
        if(cached.isPresent() || closed || invalidations>0 || invalidationFailed)return CompletableFuture.completedFuture(cached);
        final long captured=epoch;
        try { return remote.get().handle((value,error)->{
            synchronized(TieredCacheProvider.this) {
                if(closed)return Optional.<T>empty();
                if(epoch!=captured || invalidations>0 || invalidationFailed)return local.get().getNow(Optional.empty());
                if(error!=null || value==null)return Optional.<T>empty();
                value.ifPresent(remember);return value;
            }
        }); } catch(RuntimeException failure) {return CompletableFuture.completedFuture(Optional.empty());}
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return read(()->memory.getVpnResult(ip),()->persistent.getVpnResult(ip),memory::rememberVpn);
    }
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) {
        return read(()->memory.getGeoResult(ip),()->persistent.getGeoResult(ip),memory::rememberGeo);
    }
    @Override public synchronized CompletableFuture<Void> addVpnResult(VpnResult value) {
        if(closed)return CompletableFuture.completedFuture(null);
        epoch++;memory.addVpnResult(value);mirror(()->persistent.addVpnResult(value));return CompletableFuture.completedFuture(null);
    }
    @Override public synchronized CompletableFuture<Void> addGeoResult(GeoResult value) {
        if(closed)return CompletableFuture.completedFuture(null);
        epoch++;memory.addGeoResult(value);mirror(()->persistent.addGeoResult(value));return CompletableFuture.completedFuture(null);
    }
    private void mirror(Supplier<CompletableFuture<Void>> operation) {
        try {operation.get().exceptionally(failure->null);}catch(RuntimeException failure){/* Memory remains authoritative. */}
    }
    private synchronized CompletableFuture<Boolean> invalidate(Supplier<CompletableFuture<Boolean>> local,Supplier<CompletableFuture<Boolean>> remote) {
        epoch++;local.get();invalidations++;
        CompletableFuture<Boolean> applied;
        try {applied=remote.get();}catch(RuntimeException failure){applied=new CompletableFuture<>();applied.completeExceptionally(failure);}
        return applied.handle((ok,failure)->{synchronized(TieredCacheProvider.this){invalidations--;if(failure!=null || !Boolean.TRUE.equals(ok))invalidationFailed=true;}return failure==null && Boolean.TRUE.equals(ok);});
    }
    @Override public CompletableFuture<Boolean> removeVpnResult(String ip){return invalidate(()->memory.removeVpnResult(ip),()->persistent.removeVpnResult(ip));}
    @Override public CompletableFuture<Boolean> removeGeoResult(String ip){return invalidate(()->memory.removeGeoResult(ip),()->persistent.removeGeoResult(ip));}
    @Override public CompletableFuture<Boolean> removeAllVpnResults(){return invalidate(memory::removeAllVpnResults,persistent::removeAllVpnResults);}
    @Override public CompletableFuture<Boolean> removeAllGeoResults(){return invalidate(memory::removeAllGeoResults,persistent::removeAllGeoResults);}
    @Override public synchronized CompletableFuture<Boolean> disband(){closed=true;epoch++;memory.disband();return persistent.disband();}
    public synchronized String describe(){return "Memory first (10000 entries); persistence="+persistent.getClass().getSimpleName()+" invalidations="+invalidations+" readBlocked="+invalidationFailed;}
}
