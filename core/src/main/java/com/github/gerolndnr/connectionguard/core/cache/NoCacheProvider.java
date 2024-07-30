package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class NoCacheProvider implements CacheProvider {
    @Override
    public CompletableFuture<Boolean> setup() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> disband() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> addVpnResult(VpnResult vpnResult) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> addGeoResult(GeoResult geoResult) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> removeVpnResult(String ipAddress) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> removeGeoResult(String ipAddress) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> removeAllVpnResults() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> removeAllGeoResults() {
        return CompletableFuture.completedFuture(true);
    }
}
