package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
@EnabledIfEnvironmentVariable(named="CG_TEST_REDIS",matches="[0-9]+")
class RedisMemoryCoherenceTest {
    /** Fence fixture setup on the real Redis push, rather than racing it against a warm-hit assertion. */
    static final class TrackedFixture extends RedisCacheProvider {
        final String observedKey;
        final CountDownLatch initialWrite = new CountDownLatch(1);
        TrackedFixture(int port, String observedKey) { super("127.0.0.1",port,null,null); this.observedKey=observedKey; }
        @Override CompletableFuture<Boolean> track(java.util.function.Consumer<String> invalidate, Runnable lost, Runnable reset) {
            return super.track(key -> {
                invalidate.accept(key);
                if (observedKey.equals(key)) initialWrite.countDown();
            },lost,reset);
        }
        void awaitInitialWrite() throws InterruptedException {
            assertTrue(initialWrite.await(4,TimeUnit.SECONDS), "Real Redis initial-write invalidation must arrive");
        }
    }
    int port(){return Integer.parseInt(System.getenv("CG_TEST_REDIS"));}
    void ready(ResilientRedisCacheProvider cache)throws Exception{
        cache.setup().get();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while((!cache.describe().contains("coherence=tracking") || !cache.describe().contains("Redis=connected")) && System.nanoTime()<end)Thread.sleep(5);
        assertTrue(cache.describe().contains("coherence=tracking"),cache.describe());
    }
    @Test void sharedFactsHydrateInlineMemoryAndRemoteDeleteOrMalformedWriteEvictsIt()throws Exception{
        String ns="coherence-"+UUID.randomUUID(),ip="192.0.2.23",key="cg:v2:"+ns+":vpn:"+ip;
        RedisCacheProvider writer=new RedisCacheProvider("127.0.0.1",port(),null,null);writer.setNamespace(ns);
        TrackedFixture readerRemote=new TrackedFixture(port(),key);
        ResilientRedisCacheProvider reader=new ResilientRedisCacheProvider(readerRemote);reader.setNamespace(ns);
        try(Jedis admin=new Jedis("127.0.0.1",port())){
            writer.setup().get();ready(reader);writer.addVpnResult(new VpnResult(ip,true)).get();readerRemote.awaitInitialWrite();
            assertTrue(reader.getVpnResult(ip).get().get().isVpn());assertTrue(reader.getVpnResult(ip).isDone());
            admin.del(key);awaitEmpty(reader,ip);assertFalse(reader.getVpnResult(ip).get().isPresent());
            writer.addVpnResult(new VpnResult(ip,true)).get();assertTrue(reader.getVpnResult(ip).get().isPresent());
            admin.setex(key,60,"{malformed-json");awaitEmpty(reader,ip);assertFalse(reader.getVpnResult(ip).get().isPresent());
        }finally{writer.removeAllVpnResults().get();reader.disband().get();writer.disband().get();}
    }
    @Test void remoteExpiryAndFlushNeverLeaveMemoryFactsAlive()throws Exception{
        String ns="expiry-"+UUID.randomUUID(),ip="192.0.2.24",key="cg:v2:"+ns+":vpn:"+ip;
        ResilientRedisCacheProvider cache=new ResilientRedisCacheProvider(new RedisCacheProvider("127.0.0.1",port(),null,null));cache.setNamespace(ns);
        try(Jedis admin=new Jedis("127.0.0.1",port())){
            ready(cache);cache.addVpnResult(new VpnResult(ip,true));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!admin.exists(key) && System.nanoTime()<end)Thread.sleep(5);assertTrue(admin.exists(key));
            assertTrue(cache.getVpnResult(ip).get().isPresent());admin.pexpire(key,1);Thread.sleep(30);awaitEmpty(cache,ip);
            assertFalse(cache.getVpnResult(ip).get().isPresent());
        }finally{cache.disband().get();}
    }
    @RepeatedTest(8) void localClearAcknowledgesRemoteDeletionAndEvictsOtherServersMemory()throws Exception{
        String ns="clear-"+UUID.randomUUID(),ip="192.0.2.25",key="cg:v2:"+ns+":vpn:"+ip;
        ResilientRedisCacheProvider one=new ResilientRedisCacheProvider(new RedisCacheProvider("127.0.0.1",port(),null,null));one.setNamespace(ns);
        TrackedFixture twoRemote=new TrackedFixture(port(),key);
        ResilientRedisCacheProvider two=new ResilientRedisCacheProvider(twoRemote);two.setNamespace(ns);
        try(Jedis admin=new Jedis("127.0.0.1",port())){
            ready(one);ready(two);one.addVpnResult(new VpnResult(ip,true));twoRemote.awaitInitialWrite();assertTrue(admin.exists(key));
            assertTrue(two.getVpnResult(ip).get().isPresent());assertTrue(two.getVpnResult(ip).isDone());
            assertTrue(one.removeVpnResult(ip).get());assertFalse(admin.exists(key));awaitEmpty(two,ip);assertFalse(one.getVpnResult(ip).get().isPresent());
        }finally{one.disband().get();two.disband().get();}
    }
    private void awaitEmpty(ResilientRedisCacheProvider cache,String ip)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(cache.getVpnResult(ip).get().isPresent() && System.nanoTime()<end)Thread.sleep(5);
        assertFalse(cache.getVpnResult(ip).get().isPresent());
    }
}
