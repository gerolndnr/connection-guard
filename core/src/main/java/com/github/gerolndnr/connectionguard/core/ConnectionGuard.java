package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.CacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoProvider;
import com.github.gerolndnr.connectionguard.core.information.InformationProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;

import java.util.ArrayList;

public class ConnectionGuard {
    private static ArrayList<VpnProvider> vpnProviders;
    private static GeoProvider geoProvider;
    private static InformationProvider informationProvider;
    private static CacheProvider cacheProvider;

    public static ArrayList<VpnProvider> getVpnProviders() {
        return vpnProviders;
    }

    public static GeoProvider getGeoProvider() {
        return geoProvider;
    }

    public static InformationProvider getInformationProvider() {
        return informationProvider;
    }

    public static CacheProvider getCacheProvider() {
        return cacheProvider;
    }
}
