package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface CacheProvider {
    CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress);
    CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress);
    CompletableFuture<Void> addVpnResult(VpnResult vpnResult);
    CompletableFuture<Void> addGeoResult(GeoResult geoResult);
    CompletableFuture<Boolean> removeVpnResult(String ipAddress);
    CompletableFuture<Boolean> removeGeoResult(String ipAddress);
    CompletableFuture<Boolean> removeAllVpnResults();
    CompletableFuture<Boolean> removeAllGeoResults();
}
