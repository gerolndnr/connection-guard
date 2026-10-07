package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Bounded serial connection, explicit ACL username, server TTL on each entry. */
public class RedisCacheProvider extends SerialCacheProvider {
    // Avoid javac 8 bridge debug-table differences between full and incremental builds.
    @Override public void setNamespace(String namespace) { if(!this.namespace.equals("cg:v2:"+namespace))stopTracking();super.setNamespace(namespace); }
    private final String host, username, password;
    private final int port;
    private final boolean tls;
    private Jedis client;
    private volatile Jedis invalidations;
    private volatile boolean coherent;
    private java.util.function.Consumer<String> invalidate;
    private Runnable lost;
    public RedisCacheProvider(String host, int port, String username, String password) { this(host, port, username, password, false); }
    public RedisCacheProvider(String host, int port, String username, String password, boolean tls) {
        this.host = host; this.port = port; this.username = username; this.password = password; this.tls = tls;
    }
    private Jedis client() {
        if (client == null || client.isBroken()) {
            stopTracking();
            if (client != null) client.close();
            DefaultJedisClientConfig config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(1000).socketTimeoutMillis(1000)
                    .user(username == null || username.isEmpty() ? null : username).password(password == null || password.isEmpty() ? null : password).ssl(tls).build();
            client = new Jedis(host, port, config);
        }
        return client;
    }
    @Override public CompletableFuture<Boolean> setup() { return submit(() -> "PONG".equals(client().ping())); }
    @Override public CompletableFuture<Boolean> disband() { stopTracking();return stop(() -> { if (client != null) client.close(); return true; }); }
    /** Optional Redis 6+ server-assisted coherence; ACL/server limitations retain remote reads. */
    CompletableFuture<Boolean> track(java.util.function.Consumer<String> invalidate,Runnable lost,Runnable reset) {
        this.invalidate=invalidate;this.lost=lost;
        return submit(()->{
            if(coherent)return true;
            stopTracking();Jedis watcher=null;
            try {
                DefaultJedisClientConfig config=DefaultJedisClientConfig.builder().connectionTimeoutMillis(1000).socketTimeoutMillis(1000)
                    .user(username==null || username.isEmpty()?null:username).password(password==null || password.isEmpty()?null:password).ssl(tls).build();
                watcher=new Jedis(host,port,config);long id=watcher.clientId();
                watcher.getConnection().sendCommand(Protocol.Command.SUBSCRIBE,"__redis__:invalidate");watcher.getConnection().getObjectMultiBulkReply();
                client().getConnection().sendCommand(Protocol.Command.CLIENT,"TRACKING","OFF");client().getConnection().getStatusCodeReply();
                client().getConnection().sendCommand(Protocol.Command.CLIENT,"TRACKING","ON","BCAST","PREFIX",namespace+":","REDIRECT",Long.toString(id),"NOLOOP");
                if(!"OK".equals(client().getConnection().getStatusCodeReply()))throw new IllegalStateException();
                reset.run();watcher.getConnection().setTimeoutInfinite();invalidations=watcher;coherent=true;
                final Jedis stream=watcher;
                Thread thread=new Thread(()->consume(stream),"ConnectionGuard-redis-invalidations");thread.setDaemon(true);thread.start();return true;
            }catch(RuntimeException unsupported){if(watcher!=null)watcher.close();coherent=false;return false;}
        });
    }
    boolean coherent(){return coherent;}
    private void consume(Jedis stream){
        try {
            while(invalidations==stream){
                List<Object> message=stream.getConnection().getObjectMultiBulkReply();
                if(message.size()!=3)throw new IllegalStateException();Object keys=message.get(2);
                if(keys==null)invalidate.accept(null);
                else if(keys instanceof List)for(Object key:(List<?>)keys){if(!(key instanceof byte[]))throw new IllegalStateException();invalidate.accept(new String((byte[])key,java.nio.charset.StandardCharsets.UTF_8));}
                else throw new IllegalStateException();
            }
        }catch(RuntimeException disconnected){if(invalidations==stream){coherent=false;invalidations=null;lost.run();}}
        finally{stream.close();}
    }
    private void stopTracking(){coherent=false;Jedis stream=invalidations;invalidations=null;if(stream!=null)stream.getConnection().disconnect();}
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        final String key = key("vpn", ip); final long ttl = ConnectionGuard.getVpnCacheExpirationTime() * 60000L;
        return submit(() -> CacheCodec.vpn(client().get(key), ip, ttl));
    }
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) {
        final String key = key("geo", ip); final long ttl = ConnectionGuard.getGeoCacheExpirationTime() * 60000L;
        return submit(() -> CacheCodec.geo(client().get(key), ip, ttl));
    }
    @Override public CompletableFuture<Void> addVpnResult(VpnResult result) { result.setCachedOn(System.currentTimeMillis());return restoreVpnResult(result); }
    @Override public CompletableFuture<Void> addGeoResult(GeoResult result) { result.setCachedOn(System.currentTimeMillis());return restoreGeoResult(result); }
    @Override public CompletableFuture<Void> restoreVpnResult(VpnResult result) { return store(key("vpn",result.getIpAddress()),CacheCodec.encode(result),result.getCachedOn()+ConnectionGuard.getVpnCacheExpirationTime()*60000L); }
    @Override public CompletableFuture<Void> restoreGeoResult(GeoResult result) { return store(key("geo",result.getIpAddress()),CacheCodec.encode(result),result.getCachedOn()+ConnectionGuard.getGeoCacheExpirationTime()*60000L); }
    private CompletableFuture<Void> store(String key,String payload,long expires) {
        return submitWrite(()->{long left=expires-System.currentTimeMillis();if(left>0)client().setex(key,Math.max(1,(left+999)/1000),payload);return null;});
    }
    @Override public CompletableFuture<Boolean> removeVpnResult(String ip) {
        final String key = key("vpn", ip); return submit(() -> { client().del(key); return true; });
    }
    @Override public CompletableFuture<Boolean> removeGeoResult(String ip) {
        final String key = key("geo", ip); return submit(() -> { client().del(key); return true; });
    }
    private CompletableFuture<Boolean> clear(String type) {
        final String prefix = namespace + ":" + type + ":*";
        return submit(() -> {
            String cursor = "0"; int rounds = 0;
            do {
                ScanResult<String> page = client().scan(cursor, new ScanParams().match(prefix).count(100));
                if (!page.getResult().isEmpty()) client().del(page.getResult().toArray(new String[0]));
                cursor = page.getCursor();
            } while (!cursor.equals("0") && ++rounds < 1000);
            return cursor.equals("0");
        });
    }
    @Override public CompletableFuture<Boolean> removeAllVpnResults() { return clear("vpn"); }
    @Override public CompletableFuture<Boolean> removeAllGeoResults() { return clear("geo"); }
}
