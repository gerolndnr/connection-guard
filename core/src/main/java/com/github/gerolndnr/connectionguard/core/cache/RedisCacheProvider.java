package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Bounded serial connection, explicit ACL username, server TTL on each entry. */
public class RedisCacheProvider extends SerialCacheProvider {
    private final String host, username, password;
    private final int port;
    private final boolean tls;
    private Jedis client;
    public RedisCacheProvider(String host, int port, String username, String password) { this(host, port, username, password, false); }
    public RedisCacheProvider(String host, int port, String username, String password, boolean tls) {
        this.host = host; this.port = port; this.username = username; this.password = password; this.tls = tls;
    }
    private Jedis client() {
        if (client == null || client.isBroken()) {
            if (client != null) client.close();
            DefaultJedisClientConfig config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(1000).socketTimeoutMillis(1000)
                    .user(username == null || username.isEmpty() ? null : username).password(password == null || password.isEmpty() ? null : password).ssl(tls).build();
            client = new Jedis(host, port, config);
        }
        return client;
    }
    @Override public CompletableFuture<Boolean> setup() { return submit(() -> "PONG".equals(client().ping())); }
    @Override public CompletableFuture<Boolean> disband() { return stop(() -> { if (client != null) client.close(); return true; }); }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        final String key = key("vpn", ip); final long ttl = ConnectionGuard.getVpnCacheExpirationTime() * 60000L;
        return submit(() -> CacheCodec.vpn(client().get(key), ip, ttl));
    }
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) {
        final String key = key("geo", ip); final long ttl = ConnectionGuard.getGeoCacheExpirationTime() * 60000L;
        return submit(() -> CacheCodec.geo(client().get(key), ip, ttl));
    }
    @Override public CompletableFuture<Void> addVpnResult(VpnResult result) {
        final String key = key("vpn", result.getIpAddress()); result.setCachedOn(System.currentTimeMillis());
        final String payload = CacheCodec.encode(result); final long ttl = ConnectionGuard.getVpnCacheExpirationTime() * 60L;
        return submit(() -> { client().setex(key, ttl, payload); return null; });
    }
    @Override public CompletableFuture<Void> addGeoResult(GeoResult result) {
        final String key = key("geo", result.getIpAddress()); result.setCachedOn(System.currentTimeMillis());
        final String payload = CacheCodec.encode(result); final long ttl = ConnectionGuard.getGeoCacheExpirationTime() * 60L;
        return submit(() -> { client().setex(key, ttl, payload); return null; });
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
