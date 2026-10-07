package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class StartingCacheProviderTest {
    @TempDir Path directory;
    final String ip="192.0.2.21";
    @Test void blockedDriverLoadingNeverBlocksLoginAndEarlyFactsFlushOnStop()throws Exception{
        CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);AtomicReference<Thread> loader=new AtomicReference<>();
        StartingCacheProvider cache=new StartingCacheProvider(()->{loader.set(Thread.currentThread());entered.countDown();try{release.await();}catch(InterruptedException failure){throw new IllegalStateException(failure);}return new TieredCacheProvider(new SQLiteCacheProvider(directory.resolve("cache.db").toString()));});
        cache.setNamespace("test");assertTrue(cache.setup().get(100,TimeUnit.MILLISECONDS));assertTrue(entered.await(1,TimeUnit.SECONDS));assertNotSame(Thread.currentThread(),loader.get());
        assertTrue(cache.describe().contains("starting"));assertTrue(cache.getVpnResult(ip).isDone());assertFalse(cache.getVpnResult(ip).get().isPresent());
        long oldest=0;
        for(int i=0;i<1000;i++){VpnResult value=new VpnResult("198.51."+(i/256)+"."+(i%256),true);cache.addVpnResult(value);if(i==0)oldest=value.getCachedOn();}
        assertTrue(cache.getVpnResult("198.51.0.0").get().isPresent());Thread.sleep(30);
        CompletableFuture<Boolean> stop=cache.disband();assertFalse(stop.isDone());release.countDown();assertTrue(stop.get(5,TimeUnit.SECONDS));assertSame(stop,cache.disband());
        SQLiteCacheProvider restart=new SQLiteCacheProvider(directory.resolve("cache.db").toString());restart.setNamespace("test");restart.setup().get();
        try{for(int i=0;i<1000;i++)assertTrue(restart.getVpnResult("198.51."+(i/256)+"."+(i%256)).get().isPresent());assertEquals(oldest,restart.getVpnResult("198.51.0.0").get().get().getCachedOn());}finally{restart.disband().get();}
    }
    @Test void invalidationBeforeStartupCompletesCannotResurrectOldDiskFacts()throws Exception{
        SQLiteCacheProvider old=new SQLiteCacheProvider(directory.resolve("cache.db").toString());old.setNamespace("test");old.setup().get();old.addVpnResult(new VpnResult(ip,false));old.disband().get();
        CountDownLatch release=new CountDownLatch(1);StartingCacheProvider cache=new StartingCacheProvider(()->{try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}return new TieredCacheProvider(new SQLiteCacheProvider(directory.resolve("cache.db").toString()));});
        cache.setNamespace("test");cache.setup();CompletableFuture<Boolean> deleted=cache.removeVpnResult(ip);assertFalse(deleted.isDone());assertFalse(cache.getVpnResult(ip).get().isPresent());release.countDown();assertTrue(deleted.get());ready(cache);assertFalse(cache.getVpnResult(ip).get().isPresent());cache.disband().get();
    }
    @Test void reloadDuringStartupDoesNotCopyOldNamespaceFacts()throws Exception{
        CountDownLatch release=new CountDownLatch(1);StartingCacheProvider cache=new StartingCacheProvider(()->{try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}return new TieredCacheProvider(new SQLiteCacheProvider(directory.resolve("cache.db").toString()));});
        cache.setNamespace("old");cache.setup();cache.addVpnResult(new VpnResult(ip,true));cache.setNamespace("new");assertFalse(cache.getVpnResult(ip).get().isPresent());cache.addVpnResult(new VpnResult(ip,false));release.countDown();ready(cache);assertFalse(cache.getVpnResult(ip).get().get().isVpn());cache.disband().get();
        SQLiteCacheProvider disk=new SQLiteCacheProvider(directory.resolve("cache.db").toString());disk.setNamespace("new");disk.setup().get();try{assertFalse(disk.getVpnResult(ip).get().get().isVpn());disk.setNamespace("old");assertFalse(disk.getVpnResult(ip).get().isPresent());}finally{disk.disband().get();}
    }
    @Test void unavailableDriverKeepsMemoryAndNeverReadsAnUnreadyBackend()throws Exception{
        StartingCacheProvider cache=new StartingCacheProvider(()->{throw new LinkageError("redact driver details");});cache.setup();for(int i=0;i<100 && cache.describe().contains("persistence=starting");i++)Thread.sleep(10);assertTrue(cache.describe().contains("unavailable"));cache.addVpnResult(new VpnResult(ip,true));assertTrue(cache.getVpnResult(ip).get().get().isVpn());assertTrue(cache.disband().get());
    }
    @Test void failedStartupDeletionCompletesAcknowledgementAndFallsBackSafely()throws Exception{
        CountDownLatch release=new CountDownLatch(1);StartingCacheProvider cache=new StartingCacheProvider(()->{try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}return new NoCacheProvider(){@Override public CompletableFuture<Boolean> removeAllVpnResults(){CompletableFuture<Boolean> result=new CompletableFuture<>();result.completeExceptionally(new IllegalStateException());return result;}};});
        cache.setup();CompletableFuture<Boolean> edit=cache.removeVpnResult(ip);release.countDown();assertFalse(edit.get(3,TimeUnit.SECONDS));assertFalse(cache.getVpnResult(ip).get().isPresent());cache.disband().get();
    }
    private void ready(StartingCacheProvider cache)throws Exception{for(int i=0;i<300 && !cache.describe().contains("persistence=ready");i++)Thread.sleep(10);assertTrue(cache.describe().contains("persistence=ready"));}
}
