package com.github.gerolndnr.connectionguard.spigot;

import com.alessiodp.libby.BukkitLibraryManager;
import com.alessiodp.libby.Library;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.spigot.listener.AsyncPlayerPreLoginListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;

public class ConnectionGuardSpigotPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // 1. Save Default Config & set logger
        saveDefaultConfig();
        ConnectionGuard.setLogger(getLogger());

        // 2. Download libraries used for vpn and geo checks
        BukkitLibraryManager libraryManager = new BukkitLibraryManager(this);

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

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);

        // 3. Download libraries used for specified cache provider and register cache provider afterward
        switch (getConfig().getString("provider.cache.type").toLowerCase()) {
            case "sqlite":
                Library sqliteLibrary = Library.builder()
                        .groupId("org.xerial")
                        .artifactId("sqlite-jdbc")
                        .version("3.46.0.0")
                        .build();
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(getDataFolder(), "cache.db").getAbsolutePath()));
                break;
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                getLogger().info("The specified cache provider is invalid. Please use SQLite or Redis.");
                setEnabled(false);
                return;
        }

        ConnectionGuard.getCacheProvider().setup();

        // 4. Add every enabled vpn provider
        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();

        if (getConfig().getBoolean("provider.vpn.proxycheck.enabled"))
            vpnProviders.add(new ProxyCheckVpnProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));

        ConnectionGuard.setVpnProviders(vpnProviders);

        // 5. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(getConfig().getInt("provider.cache.expiration.geo"));

        // 6. Register bukkit listener
        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);
    }

    @Override
    public void onDisable() {
        ConnectionGuard.getCacheProvider().disband();
    }
}
