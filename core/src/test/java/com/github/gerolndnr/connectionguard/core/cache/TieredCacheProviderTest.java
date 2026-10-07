package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TieredCacheProviderTest {
    @TempDir Path directory;
    String ip="192.0.2.12";
    SQLiteCacheProvider disk(){return new SQLiteCacheProvider(directory.resolve("cache.db").toString());}
    TieredCacheProvider create()throws Exception{TieredCacheProvider result=new TieredCacheProvider(disk());result.setNamespace("fixture");assertTrue(result.setup().get());return result;}
    @Test void restartHydratesMemoryWithoutResettingAgeOrSharingMutableObjects()throws Exception{
        TieredCacheProvider first=create();first.addVpnResult(new VpnResult(ip,true));assertTrue(first.disband().get());
        TieredCacheProvider second=create();
        try{
            VpnResult disk=second.getVpnResult(ip).get().get();long cached=disk.getCachedOn();disk.setVpn(false);
            CompletableFuture<Optional<VpnResult>> hit=second.getVpnResult(ip);assertTrue(hit.isDone());
            assertTrue(hit.get().get().isVpn());assertEquals(cached,hit.get().get().getCachedOn());
        }finally{second.disband().get();}
    }
    @Test void thousandWritesFlushOnCleanStopAndRemainAfterRestart()throws Exception{
        TieredCacheProvider first=create();
        for(int i=0;i<1000;i++)first.addVpnResult(new VpnResult("198.51."+(i/256)+"."+(i%256),i%2==0));
        assertTrue(first.disband().get());SQLiteCacheProvider second=disk();second.setNamespace("fixture");assertTrue(second.setup().get());
        try{for(int i=0;i<1000;i++)assertEquals(i%2==0,second.getVpnResult("198.51."+(i/256)+"."+(i%256)).get().get().isVpn());}
        finally{second.disband().get();}
    }
    @Test void clearRemovesBothTiersAndPendingWritesCannotResurrectThem()throws Exception{
        TieredCacheProvider cache=create();
        try{
            cache.addVpnResult(new VpnResult(ip,true));cache.addGeoResult(new GeoResult(ip,"DE","Fixture","Fixture"));
            assertTrue(cache.removeVpnResult(ip).get());assertFalse(cache.getVpnResult(ip).get().isPresent());assertTrue(cache.getGeoResult(ip).get().isPresent());
            cache.addVpnResult(new VpnResult(ip,true));assertTrue(cache.removeAllVpnResults().get());assertFalse(cache.getVpnResult(ip).get().isPresent());
            assertTrue(cache.removeAllGeoResults().get());assertFalse(cache.getGeoResult(ip).get().isPresent());
        }finally{cache.disband().get();}
        SQLiteCacheProvider restarted=disk();restarted.setNamespace("fixture");restarted.setup().get();
        try{assertFalse(restarted.getVpnResult(ip).get().isPresent());assertFalse(restarted.getGeoResult(ip).get().isPresent());}finally{restarted.disband().get();}
    }
    @Test void invalidatedInflightReadCannotRepublishStaleFacts()throws Exception{
        CompletableFuture<Optional<VpnResult>> old=new CompletableFuture<>();AtomicInteger reads=new AtomicInteger();
        NoCacheProvider backend=new NoCacheProvider(){@Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ignored){reads.incrementAndGet();return old;}};
        TieredCacheProvider cache=new TieredCacheProvider(backend);cache.setNamespace("a");cache.setup().get();
        CompletableFuture<Optional<VpnResult>> pending=cache.getVpnResult(ip);cache.removeVpnResult(ip).get();
        VpnResult value=new VpnResult(ip,false);value.setCachedOn(System.currentTimeMillis());old.complete(Optional.of(value));assertFalse(pending.get().isPresent());cache.disband().get();
    }
    @Test void slowInvalidationAndFailedInvalidationNeverReadDeletedBackendFacts()throws Exception{
        CompletableFuture<Boolean> removed=new CompletableFuture<>();AtomicInteger reads=new AtomicInteger();
        NoCacheProvider backend=new NoCacheProvider(){@Override public CompletableFuture<Boolean> removeVpnResult(String ignored){return removed;}@Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ignored){reads.incrementAndGet();return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip,false)));}};
        TieredCacheProvider cache=new TieredCacheProvider(backend);cache.setup().get();CompletableFuture<Boolean> edit=cache.removeVpnResult(ip);
        assertFalse(cache.getVpnResult(ip).get().isPresent());assertEquals(0,reads.get());removed.complete(false);assertFalse(edit.get());assertFalse(cache.getVpnResult(ip).get().isPresent());assertEquals(0,reads.get());cache.disband().get();
    }
    @Test void namespaceChangeFencesInflightReadAndKeepsDiskNamespacesSeparate()throws Exception{
        TieredCacheProvider cache=create();try{cache.addVpnResult(new VpnResult(ip,true));cache.setNamespace("b");assertFalse(cache.getVpnResult(ip).get().isPresent());cache.addVpnResult(new VpnResult(ip,false));cache.setNamespace("fixture");assertTrue(cache.getVpnResult(ip).get().get().isVpn());}finally{cache.disband().get();}
    }
    @Test void cacheAndLocalDecisionCompleteOnCallerWithoutLookupWorkers()throws Exception{
        TieredCacheProvider cache=create();ConnectionGuard.setCacheProvider(cache);ConnectionGuard.setVpnProviders(new ArrayList<>());ConnectionGuard.configureLookup(LookupSettings.defaults());
        try{cache.addVpnResult(new VpnResult(ip,true));Thread caller=Thread.currentThread();AtomicReference<Thread> completion=new AtomicReference<>();
            CompletableFuture<VpnResult> result=ConnectionGuard.getVpnResult(ip);result.thenAccept(value->completion.set(Thread.currentThread()));assertTrue(result.isDone());assertSame(caller,completion.get());assertEquals(0,ConnectionGuard.getLookupRuntime().getActiveWorkers());
        }finally{cache.disband().get();ConnectionGuard.setCacheProvider(new NoCacheProvider());}
    }
    @Test void freshWriteAndPersistentMirrorShareExactlyOneTimestamp()throws Exception{
        MemoryCacheProvider storage=new MemoryCacheProvider();AtomicLong mirrored=new AtomicLong();
        CacheProvider backend=new NoCacheProvider(){
            @Override public CompletableFuture<Void> addVpnResult(VpnResult value){fail("A second tier must not re-date facts");return CompletableFuture.completedFuture(null);}
            @Override public CompletableFuture<Void> restoreVpnResult(VpnResult value){mirrored.set(value.getCachedOn());return storage.restoreVpnResult(value);}
        };
        TieredCacheProvider cache=new TieredCacheProvider(backend);cache.setup();VpnResult value=new VpnResult(ip,true);cache.addVpnResult(value);
        assertTrue(mirrored.get()>0);assertEquals(value.getCachedOn(),mirrored.get());assertEquals(mirrored.get(),cache.getVpnResult(ip).get().get().getCachedOn());assertEquals(mirrored.get(),storage.getVpnResult(ip).get().get().getCachedOn());cache.disband().get();
    }
    @Test void expiredSourceFactsDoNotBecomeFreshWhenHydrated()throws Exception{
        TieredCacheProvider cache=create();try{VpnResult value=new VpnResult(ip,true);value.setValidUntil(System.currentTimeMillis()+50);cache.addVpnResult(value);Thread.sleep(75);assertFalse(cache.getVpnResult(ip).get().isPresent());}finally{cache.disband().get();}
    }
}
