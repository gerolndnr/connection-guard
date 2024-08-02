package com.github.gerolndnr.connectionguard.bungee;

import com.alessiodp.libby.BungeeLibraryManager;
import com.alessiodp.libby.Library;
import com.github.gerolndnr.connectionguard.bungee.listener.ConnectionGuardBungeeListener;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.IpApiGeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;

public class ConnectionGuardBungeePlugin extends Plugin {
    private static ConnectionGuardBungeePlugin connectionGuardBungeePlugin;

    @Override
    public void onEnable() {
        connectionGuardBungeePlugin = this;

        BungeeLibraryManager libraryManager = new BungeeLibraryManager(this);
        Library httpLibrary = Library.builder()
                .groupId("com.squareup.okhttp3")
                .artifactId("okhttp")
                .version("4.12.0")
                .resolveTransitiveDependencies(true)
                .build();
        Library gsonLibrary = Library.builder()
                .groupId("com.google.code.gson")
                .artifactId("gson")
                .version("2.11.0")
                .resolveTransitiveDependencies(true)
                .build();
        Library sqliteLibrary = Library.builder()
                .groupId("org.xerial")
                .artifactId("sqlite-jdbc")
                .version("3.46.0.0")
                .resolveTransitiveDependencies(true)
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        libraryManager.loadLibrary(sqliteLibrary);

        ConnectionGuard.setLogger(getLogger());
        ConnectionGuard.setGeoProvider(new IpApiGeoProvider());
        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();
        vpnProviders.add(new ProxyCheckVpnProvider(""));
        ConnectionGuard.setVpnProviders(vpnProviders);
        ConnectionGuard.setGeoCacheExpirationTime(1440);
        ConnectionGuard.setVpnCacheExpirationTime(1440);
        ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(getDataFolder(), "cache.db").getPath()));
        ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.getCacheProvider().setup();

        getProxy().getPluginManager().registerListener(this, new ConnectionGuardBungeeListener());
    }

    @Override
    public void onDisable() {
        ConnectionGuard.getCacheProvider().disband();
    }

    public static ConnectionGuardBungeePlugin getInstance() {
        return connectionGuardBungeePlugin;
    }
}
