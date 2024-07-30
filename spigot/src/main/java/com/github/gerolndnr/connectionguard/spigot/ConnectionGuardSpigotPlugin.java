package com.github.gerolndnr.connectionguard.spigot;

import com.alessiodp.libby.BukkitLibraryManager;
import com.alessiodp.libby.Library;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.spigot.listener.AsyncPlayerPreLoginListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;

public class ConnectionGuardSpigotPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
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

        ConnectionGuard.setCacheProvider(new NoCacheProvider());
        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();
        vpnProviders.add(new ProxyCheckVpnProvider(""));
        ConnectionGuard.setVpnProviders(vpnProviders);
        ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.setLogger(getLogger());

        ConnectionGuard.getCacheProvider().setup();

        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);
    }

    @Override
    public void onDisable() {
        ConnectionGuard.getCacheProvider().disband();
    }
}
