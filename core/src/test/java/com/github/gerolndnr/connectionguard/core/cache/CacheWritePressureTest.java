package com.github.gerolndnr.connectionguard.core.cache;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CacheWritePressureTest {
    @Test void fullWriteQueueDropsBestEffortCopiesButCannotDropShutdown()throws Exception{
        RedisCacheProvider cache=new RedisCacheProvider("127.0.0.1",1,null,null);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);List<CompletableFuture<Void>> writes=new ArrayList<>();
        try{
            writes.add(cache.submitWrite(()->{blocked.countDown();release.await();return null;}));assertTrue(blocked.await(1,TimeUnit.SECONDS));
            for(int i=0;i<1000;i++)writes.add(cache.submitWrite(()->null));
            assertTrue(writes.stream().filter(CompletableFuture::isDone).count()>=936);assertTrue(writes.stream().noneMatch(CompletableFuture::isCompletedExceptionally));
            CompletableFuture<Boolean> stop=cache.disband();assertFalse(stop.isDone());release.countDown();assertTrue(stop.get(3,TimeUnit.SECONDS));
            for(CompletableFuture<Void> write:writes)write.get();assertSame(stop,cache.disband());
        }finally{release.countDown();cache.disband().get(3,TimeUnit.SECONDS);}
    }
}
