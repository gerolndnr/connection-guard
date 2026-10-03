package com.github.gerolndnr.connectionguard.spigot;

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.RedisCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.IpApiGeoProvider;
import com.github.gerolndnr.connectionguard.core.geo.ProxyCheckGeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.vpn.custom.CustomVpnProvider;
import com.github.gerolndnr.connectionguard.spigot.commands.ConnectionGuardSpigotCommand;
import com.github.gerolndnr.connectionguard.spigot.listener.AsyncPlayerPreLoginListener;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

public class ConnectionGuardSpigotPlugin extends JavaPlugin {
    private static ConnectionGuardSpigotPlugin connectionGuardSpigotPlugin;
    private volatile YamlConfiguration activeConfig;
    private File languageFile;
    private volatile YamlConfiguration languageConfig;
    private HashMap<String, VpnProvider> vpnProviderMap;
    private PlatformTasks platformTasks;
    private PlatformMetrics metrics;
    public PlatformTasks tasks() { return platformTasks; }

    @Override
    public void onLoad() {
        connectionGuardSpigotPlugin = this;

        vpnProviderMap = new HashMap<>();
    }

    @Override
    public void onEnable() {
        platformTasks = new PlatformTasks(this);
        getLogger().info("Platform task dispatch: " + platformTasks.mode());
        // 1. Save Default Config & set logger
        saveDefaultConfig();
        activeConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));

        String selectedLanguageFileName = getConfig().getString("message-language") + ".yml";
        if (!new File(getDataFolder(), "translation").exists()) {
            saveResource("translation" + File.separator + "en.yml", false);
        }
        languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), selectedLanguageFileName);
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);

        ConnectionGuard.setLogger(getLogger());

        // 2. Download libraries used for vpn and geo checks
        BukkitLibraryManager libraryManager = new BukkitLibraryManager(this);

        Library gsonLibrary = Library.builder()
                .groupId("com.google.code.gson")
                .artifactId("gson")
                .version("2.11.0")
                .relocate("com{}google{}gson", "com{}github{}gerolndnr{}connectionguard{}libs{}com{}google{}gson")
                .build();

        libraryManager.addMavenCentral();
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
            case "redis":
                Library jedisLibrary = Library.builder()
                        .groupId("redis.clients")
                        .artifactId("jedis")
                        .version("5.0.0")
                        .build();
                libraryManager.loadLibrary(jedisLibrary);
                ConnectionGuard.setCacheProvider(
                        new RedisCacheProvider(
                                getConfig().getString("provider.cache.redis.hostname"),
                                getConfig().getInt("provider.cache.redis.port"),
                                getConfig().getString("provider.cache.redis.username"),
                                getConfig().getString("provider.cache.redis.password"),
                                GuardSettings.bool(path -> getConfig().get(path, null), "provider.cache.redis.tls", false)
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

        ProviderConfiguration draft = new ProviderConfiguration(path -> getConfig().get(path, null), new ArrayList<>(getConfig().getConfigurationSection("provider.vpn").getKeys(false)), getDataFolder().toPath());
        ConnectionGuard.applyProviders(draft);
        ConnectionGuard.initializeCache();
        ConnectionGuard.initializeRules(getDataFolder().toPath());


        // 6. Register bukkit listener
        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);

        // 7. Register commands
        getCommand("connectionguard").setExecutor(new ConnectionGuardSpigotCommand());
        getCommand("connectionguard").setTabCompleter(new ConnectionGuardSpigotCommand());


        metrics = new PlatformMetrics(this, platformTasks, 22911);
    }

    @Override
    public void onDisable() {
        if (metrics != null) metrics.close();
        if (platformTasks != null) platformTasks.close();
        ConnectionGuard.shutdown();
        com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.shutdown();
        if (ConnectionGuard.getCacheProvider() != null) ConnectionGuard.getCacheProvider().disband();
    }

    public YamlConfiguration getLanguageConfig() {
        return languageConfig;
    }

    @Override public org.bukkit.configuration.file.FileConfiguration getConfig() { return activeConfig != null ? activeConfig : super.getConfig(); }
    public void reloadAllConfigs() {
        YamlConfiguration next = new YamlConfiguration();
        try { next.load(new File(getDataFolder(), "config.yml")); }
        catch (Exception invalid) { throw new IllegalArgumentException("Configuration file is invalid; active settings preserved."); }
        ProviderConfiguration draft = new ProviderConfiguration(path -> next.get(path, null), new ArrayList<>(next.getConfigurationSection("provider.vpn").getKeys(false)), getDataFolder().toPath());
        ConnectionGuard.applyProviders(draft);
        activeConfig = next;
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);
    }

    public static ConnectionGuardSpigotPlugin getInstance() {
        return connectionGuardSpigotPlugin;
    }
}
