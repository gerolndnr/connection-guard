package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.Gson;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class RedisCacheProvider implements CacheProvider {
    private String hostname;
    private int port;
    private String username;
    private String password;
    private JedisPooled jedisPooled;

    public RedisCacheProvider(String hostname, int port, String username, String password) {
        this.hostname = hostname;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public CompletableFuture<Boolean> setup() {
        return CompletableFuture.supplyAsync(() -> {
            jedisPooled = new JedisPooled(hostname, port, null, password);

            String pong = jedisPooled.ping();
            if (pong.equalsIgnoreCase("pong")) {
                return true;
            } else {
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> disband() {
        return CompletableFuture.supplyAsync(() -> {
            jedisPooled.close();
            return true;
        });
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            String vpnResultRaw = jedisPooled.hget("connectionguard.vpn", ipAddress);

            if (vpnResultRaw == null) {
                return Optional.empty();
            }

            Gson gson = new Gson();
            VpnResult vpnResult = gson.fromJson(vpnResultRaw, VpnResult.class);

            if ((vpnResult.getCachedOn() + ConnectionGuard.getVpnCacheExpirationTime() * 60 * 1000) > new Date().getTime()) {
                return Optional.of(vpnResult);
            } else {
                jedisPooled.hdel("connectionguard.vpn", ipAddress);
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            String geoResultRaw = jedisPooled.hget("connectionguard.geo", ipAddress);
            if (geoResultRaw == null) {
                return Optional.empty();
            }
            Gson gson = new Gson();
            GeoResult geoResult = gson.fromJson(geoResultRaw, GeoResult.class);

            if ((geoResult.getCachedOn() + ConnectionGuard.getVpnCacheExpirationTime() * 60 * 1000) > new Date().getTime()) {
                return Optional.of(geoResult);
            } else {
                jedisPooled.hdel("connectionguard.geo", ipAddress);
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Void> addVpnResult(VpnResult vpnResult) {
        return CompletableFuture.runAsync(() -> {
            VpnResult vpnResult1 = vpnResult;
            vpnResult1.setCachedOn(new Date().getTime());
            Gson gson = new Gson();
            jedisPooled.hset("connectionguard.vpn",  vpnResult.getIpAddress(), gson.toJson(vpnResult1));
        });
    }

    @Override
    public CompletableFuture<Void> addGeoResult(GeoResult geoResult) {
        return CompletableFuture.runAsync(() -> {
            GeoResult geoResult1 = geoResult;
            geoResult1.setCachedOn(new Date().getTime());
            Gson gson = new Gson();
            jedisPooled.hset("connectionguard.vpn", "connectionguard.geo." + geoResult.getIpAddress(), gson.toJson(geoResult1));
        });
    }

    @Override
    public CompletableFuture<Boolean> removeVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            jedisPooled.hdel("connectionguard.vpn", ipAddress);
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> removeGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            jedisPooled.hdel("connectionguard.geo", ipAddress);
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> removeAllVpnResults() {
        return CompletableFuture.supplyAsync(() -> {
            jedisPooled.del("connectionguard.vpn");
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> removeAllGeoResults() {
        return CompletableFuture.supplyAsync(() -> {
            jedisPooled.del("connectionguard.geo");
            return true;
        });
    }
}
