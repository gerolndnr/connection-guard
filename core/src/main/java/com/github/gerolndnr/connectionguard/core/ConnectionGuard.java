package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.CacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;

import java.util.ArrayList;

public class ConnectionGuard {
    private static ArrayList<VpnProvider> vpnProviders;
    private static GeoProvider geoProvider;
    private static CacheProvider cacheProvider;

    public static void setVpnProviders(ArrayList<VpnProvider> vpnProviders) {
        ConnectionGuard.vpnProviders = vpnProviders;
    }

    public static void setGeoProvider(GeoProvider geoProvider) {
        ConnectionGuard.geoProvider = geoProvider;
    }

    public static void setCacheProvider(CacheProvider cacheProvider) {
        ConnectionGuard.cacheProvider = cacheProvider;
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
}
