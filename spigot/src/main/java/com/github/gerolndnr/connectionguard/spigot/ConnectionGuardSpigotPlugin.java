package com.github.gerolndnr.connectionguard.spigot;

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.RedisCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.ResilientRedisCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.MemoryCacheProvider;
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
        try { enableGuard(); }
        catch (RuntimeException | LinkageError failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.STARTUP); throw failure; }
    }
    private void enableGuard() {
        platformTasks = new PlatformTasks(this);
        getLogger().info("Platform task dispatch: " + platformTasks.mode());
        com.github.gerolndnr.connectionguard.core.migration.MigrationBootstrap.beforeStart(getDataFolder().toPath(), getLogger()::warning);

        // 1. Save Default Config & set logger
        boolean existingInstallation = java.nio.file.Files.exists(getDataFolder().toPath().resolve("config.yml"));
        saveDefaultConfig();
        activeConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.prepareErrorReports(getDataFolder().toPath(), path -> getConfig().get(path, null));
        // Dashboard settings layer over config.yml in memory; the file is never written.
        com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(getDataFolder().toPath(), activeConfig::set);

        com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<YamlConfiguration> selectedMessages = loadMessages(activeConfig.get("message-language"));
        languageFile = selectedMessages.file.toFile();
        languageConfig = selectedMessages.document;

        ConnectionGuard.setLogger(getLogger());

        // 2. Download libraries used for vpn and geo checks
        BukkitLibraryManager libraryManager = new BukkitLibraryManager(this);


        libraryManager.addMavenCentral();
        com.github.gerolndnr.connectionguard.core.migration.MigrationDatabases.setLoader(id -> {
            if (id.equals("h2")) {
                if (Integer.parseInt(System.getProperty("java.specification.version", "1.8").replace("1.", "")) < 11) throw new IllegalStateException("H2 migration requires Java 11+.");
                libraryManager.loadLibrary(Library.builder().groupId("com.h2database").artifactId("h2").version("2.4.240").build());
            } else if (id.equals("sqlite")) libraryManager.loadLibrary(Library.builder().groupId("org.xerial").artifactId("sqlite-jdbc").version("3.46.0.0").build());
            else throw new IllegalArgumentException("Unknown migration driver.");
        });

        // 3. Download libraries used for specified cache provider and register cache provider afterward
        switch (getConfig().getString("provider.cache.type").toLowerCase()) {
            case "sqlite":
                Library sqliteLibrary = Library.builder()
                        .groupId("org.xerial")
                        .artifactId("sqlite-jdbc")
                        .version("3.46.0.0")
                        .build();
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new com.github.gerolndnr.connectionguard.core.cache.TieredCacheProvider(new SQLiteCacheProvider(new File(getDataFolder(), "cache.db").getAbsolutePath())));
                break;
            case "redis":
                Library jedisLibrary = Library.builder()
                        .groupId("redis.clients")
                        .artifactId("jedis")
                        .version("5.0.0")
                        .build();
                libraryManager.loadLibrary(jedisLibrary);
                ConnectionGuard.setCacheProvider(
                        new ResilientRedisCacheProvider(new RedisCacheProvider(
                                getConfig().getString("provider.cache.redis.hostname"),
                                getConfig().getInt("provider.cache.redis.port"),
                                getConfig().getString("provider.cache.redis.username"),
                                getConfig().getString("provider.cache.redis.password"),
                                GuardSettings.bool(path -> getConfig().get(path, null), "provider.cache.redis.tls", false)
                        ))

                );
                break;
            case "memory":
                ConnectionGuard.setCacheProvider(new MemoryCacheProvider());
                break;
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                getLogger().info("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
                setEnabled(false);
                return;
        }

        ProviderConfiguration draft = ProviderConfiguration.forStartup(path -> getConfig().get(path, null), new ArrayList<>(getConfig().getConfigurationSection("provider.vpn").getKeys(false)), getDataFolder().toPath(), selectedMessages.messages);
        ConnectionGuard.applyProviders(draft);
        ConnectionGuard.initializeCache();
        ConnectionGuard.initializeRules(getDataFolder().toPath());
        com.github.gerolndnr.connectionguard.core.config.OperationModeNotice.show(getDataFolder().toPath(), existingInstallation, draft.settings.observe, ConnectionGuard.getLogger());
        com.github.gerolndnr.connectionguard.core.config.KeylessProviderNotice.show(getDataFolder().toPath(), existingInstallation, ConnectionGuard.getLogger());
        com.github.gerolndnr.connectionguard.core.config.IntelProviderNotice.show(getDataFolder().toPath(), existingInstallation, ConnectionGuard.getLogger());
        ConnectionGuard.startTorRefresh();
        ConnectionGuard.startCoverageReporting();
        // Optional dashboard link: background only, never on the login path.
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.setReloadHook(this::reloadAllConfigs);
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.setNoticeConsole(notice -> platformTasks.global(() -> {
            if (!com.github.gerolndnr.connectionguard.core.cloud.CloudSync.noticeCurrent(notice)) return;
            java.util.List<String> lines = notice.consoleLines();
            for (int i = 0; i < lines.size(); i++) {
                String color = i == 1 ? "\u00a7b\u00a7l" : i == 3 ? "\u00a7a\u00a7l" : i == 4 ? "\u00a7a\u00a7n" : "\u00a77";
                org.bukkit.Bukkit.getConsoleSender().sendMessage(color + lines.get(i) + "\u00a7r");
            }
        }));
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.start(getDataFolder().toPath(), path -> getConfig().get(path, null), com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Platform.BUKKIT,
                getServer().getName() + " " + getServer().getVersion(), getDescription().getVersion(), getLogger());


        // 6. Register bukkit listener
        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);
        getServer().getPluginManager().registerEvents(new com.github.gerolndnr.connectionguard.spigot.listener.CloudDashboardNoticeListener(), this);

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
        ConnectionGuard.closeCache();
    }

    public YamlConfiguration getLanguageConfig() {
        return languageConfig;
    }

    @Override public org.bukkit.configuration.file.FileConfiguration getConfig() { return activeConfig != null ? activeConfig : super.getConfig(); }
    public void reloadAllConfigs() {
        try { reloadGuardConfigs(); }
        catch (RuntimeException | LinkageError failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.RELOAD); throw failure; }
    }
    private void reloadGuardConfigs() {
        synchronized (com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.reloadLock()) {
            YamlConfiguration next = new YamlConfiguration();
            try { next.load(new File(getDataFolder(), "config.yml")); }
            catch (Exception invalid) { throw new IllegalArgumentException("Configuration file is invalid; active settings preserved."); }
            com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(getDataFolder().toPath(), next::set);
            com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<YamlConfiguration> selectedMessages = loadMessages(next.get("message-language"));
            ProviderConfiguration draft = new ProviderConfiguration(path -> next.get(path, null), new ArrayList<>(next.getConfigurationSection("provider.vpn").getKeys(false)), getDataFolder().toPath(), selectedMessages.messages);
            synchronized (com.github.gerolndnr.connectionguard.core.ConnectionGuard.class) {
                com.github.gerolndnr.connectionguard.core.cloud.CloudSync.validateReloadActivation();
                ConnectionGuard.applyProviders(draft);
                activeConfig = next;
                languageFile = selectedMessages.file.toFile();
                languageConfig = selectedMessages.document;
                com.github.gerolndnr.connectionguard.core.cloud.CloudSync.refresh();
            }
        }
    }

    private com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<org.bukkit.configuration.file.YamlConfiguration> loadMessages(Object language) {
        return com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.load(getDataFolder().toPath(), language, new com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Parser<org.bukkit.configuration.file.YamlConfiguration>() {
            public org.bukkit.configuration.file.YamlConfiguration parse(java.nio.file.Path file) throws Exception {
                org.bukkit.configuration.file.YamlConfiguration document = new org.bukkit.configuration.file.YamlConfiguration();
                try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) { document.load(reader); }
                return document;
            }
            public Object value(org.bukkit.configuration.file.YamlConfiguration document, String key) { return document.get(key, null); }
        });
    }

    public static ConnectionGuardSpigotPlugin getInstance() {
        return connectionGuardSpigotPlugin;
    }
}
