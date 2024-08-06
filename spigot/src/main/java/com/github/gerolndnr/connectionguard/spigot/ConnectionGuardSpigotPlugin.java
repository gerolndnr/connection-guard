package com.github.gerolndnr.connectionguard.spigot;

import com.alessiodp.libby.BukkitLibraryManager;
import com.alessiodp.libby.Library;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.RedisCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.IpApiGeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.spigot.commands.ConnectionGuardSpigotCommand;
import com.github.gerolndnr.connectionguard.spigot.listener.AsyncPlayerPreLoginListener;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;

public class ConnectionGuardSpigotPlugin extends JavaPlugin {
    private static ConnectionGuardSpigotPlugin connectionGuardSpigotPlugin;
    private File languageFile;
    private YamlConfiguration languageConfig;

    @Override
    public void onEnable() {
        connectionGuardSpigotPlugin = this;

        // 1. Save Default Config & set logger
        saveDefaultConfig();

        String selectedLanguageFileName = getConfig().getString("message-language") + ".yml";
        if (!new File(getDataFolder(), "translation").exists()) {
            saveResource("translation" + File.separator + "en.yml", false);
        }
        languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), selectedLanguageFileName);
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);

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
                        .resolveTransitiveDependencies(true)
                        .build();
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(getDataFolder(), "cache.db").getAbsolutePath()));
                break;
            case "redis":
                Library jedisLibrary = Library.builder()
                        .groupId("redis.clients")
                        .artifactId("jedis")
                        .version("5.0.0")
                        .resolveTransitiveDependencies(true)
                        .build();
                libraryManager.loadLibrary(jedisLibrary);
                ConnectionGuard.setCacheProvider(
                        new RedisCacheProvider(
                                getConfig().getString("provider.cache.redis.hostname"),
                                getConfig().getInt("provider.cache.redis.port"),
                                getConfig().getString("provider.cache.redis.username"),
                                getConfig().getString("provider.cache.redis.password")
                        )
                );
                break;
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                getLogger().info("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
                setEnabled(false);
                return;
        }

        ConnectionGuard.getCacheProvider().setup();

        // 4. Add every enabled vpn provider and geo provider
        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();

        if (getConfig().getBoolean("provider.vpn.proxycheck.enabled"))
            vpnProviders.add(new ProxyCheckVpnProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));
        if (getConfig().getBoolean("provider.vpn.ip-api.enabled"))
            vpnProviders.add(new IpApiVpnProvider());
        if (getConfig().getBoolean("provider.vpn.iphub.enabled"))
            vpnProviders.add(new IpHubVpnProvider(getConfig().getString("provider.vpn.iphub.api-key")));
        if (getConfig().getBoolean("provider.vpn.vpnapi.enabled"))
            vpnProviders.add(new VpnApiVpnProvider(getConfig().getString("provider.vpn.vpnapi.api-key")));

        ConnectionGuard.setVpnProviders(vpnProviders);

        switch (getConfig().getString("provider.geo.service").toLowerCase()) {
            case "ip-api":
                ConnectionGuard.setGeoProvider(new IpApiGeoProvider());
                break;
            default:
                getLogger().info("The specified geo provider is invalid. Please use IP-API.");
        }

        // 5. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(getConfig().getInt("provider.cache.expiration.geo"));

        // 6. Register bukkit listener
        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);

        // 7. Register commands
        getCommand("connectionguard").setExecutor(new ConnectionGuardSpigotCommand());
        getCommand("connectionguard").setTabCompleter(new ConnectionGuardSpigotCommand());
    }

    @Override
    public void onDisable() {
        ConnectionGuard.getCacheProvider().disband();
    }

    public YamlConfiguration getLanguageConfig() {
        return languageConfig;
    }

    public void reloadAllConfigs() {
        reloadConfig();
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);
    }

    public static ConnectionGuardSpigotPlugin getInstance() {
        return connectionGuardSpigotPlugin;
    }
}