package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.CacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ConnectionGuard {
    private static int requiredPositiveFlags = 1;
    private static ArrayList<VpnProvider> vpnProviders;
    private static GeoProvider geoProvider;
    private static CacheProvider cacheProvider;
    private static Logger logger;
    private static int vpnCacheExpirationTime = 1440;
    private static int geoCacheExpirationTime = 1440;

    public static CompletableFuture<VpnResult> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<VpnResult> vpnResultOptional = cacheProvider.getVpnResult(ipAddress).join();
            Optional<String> vpnProviderName = Optional.empty();

            if (vpnResultOptional.isPresent())
                return vpnResultOptional.get();

            int vpnPositives = 0;
            ArrayList<CompletableFuture<Optional<VpnResult>>> vpnResultList = new ArrayList<>();

            for (VpnProvider vpnProvider : vpnProviders) {
                vpnResultList.add(vpnProvider.getVpnResult(ipAddress));
            }

            CompletableFuture.allOf(vpnResultList.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<Optional<VpnResult>> vpnResultCompleted : vpnResultList) {
                if (vpnResultCompleted.join().isPresent()) {
                    if (vpnResultCompleted.join().get().getVpnProviderName().isPresent()) {
                        vpnProviderName = vpnResultCompleted.join().get().getVpnProviderName();
                    }
                    if (vpnResultCompleted.join().get().isVpn())
                        vpnPositives++;
                }
            }

            VpnResult computedVpnResult = new VpnResult(ipAddress, false, vpnProviderName);

            computedVpnResult.setVpn(vpnPositives >= requiredPositiveFlags);

            cacheProvider.addVpnResult(computedVpnResult).join();
            return computedVpnResult;
        });
    }

    public static CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<GeoResult> geoResultOptional = cacheProvider.getGeoResult(ipAddress).join();

            if (geoResultOptional.isPresent())
                return geoResultOptional;

            geoResultOptional = geoProvider.getGeoResult(ipAddress).join();

            return geoResultOptional;
        });
    }

    public static void setRequiredPositiveFlags(int requiredPositiveFlags) {
        ConnectionGuard.requiredPositiveFlags = requiredPositiveFlags;
    }

    public static void setVpnProviders(ArrayList<VpnProvider> vpnProviders) {
        ConnectionGuard.vpnProviders = vpnProviders;
    }

    public static void setGeoProvider(GeoProvider geoProvider) {
        ConnectionGuard.geoProvider = geoProvider;
    }

    public static void setCacheProvider(CacheProvider cacheProvider) {
        ConnectionGuard.cacheProvider = cacheProvider;
    }

    public static void setLogger(Logger logger) {
        ConnectionGuard.logger = logger;
    }

    public static void setVpnCacheExpirationTime(int vpnCacheExpirationTime) {
        ConnectionGuard.vpnCacheExpirationTime = vpnCacheExpirationTime;
    }

    public static void setGeoCacheExpirationTime(int geoCacheExpirationTime) {
        ConnectionGuard.geoCacheExpirationTime = geoCacheExpirationTime;
    }

    public static int getRequiredPositiveFlags() {
        return requiredPositiveFlags;
    }

    public static ArrayList<VpnProvider> getVpnProviders() {
        return vpnProviders;
    }

    public static GeoProvider getGeoProvider() {
        return geoProvider;
    }

    public static CacheProvider getCacheProvider() {
        return cacheProvider;
    }

    public static Logger getLogger() {
        return logger;
    }

    public static int getVpnCacheExpirationTime() {
        return vpnCacheExpirationTime;
    }

    public static int getGeoCacheExpirationTime() {
        return geoCacheExpirationTime;
    }
}
