package com.github.gerolndnr.connectionguard.bungee;

import net.byteflux.libby.BungeeLibraryManager;
import net.byteflux.libby.Library;
import com.github.gerolndnr.connectionguard.bungee.commands.ConnectionGuardBungeeCommand;
import com.github.gerolndnr.connectionguard.bungee.listener.ConnectionGuardBungeeListener;
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
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.bstats.bungeecord.Metrics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

public class ConnectionGuardBungeePlugin extends Plugin {
    private static ConnectionGuardBungeePlugin connectionGuardBungeePlugin;
    private File configFile;
    private File languageFile;
    private volatile Configuration config;
    private volatile Configuration languageConfig;

    private HashMap<String, VpnProvider> vpnProviderMap;

    @Override
    public void onEnable() {
        connectionGuardBungeePlugin = this;
        vpnProviderMap = new HashMap<>();

        // 1. Set logger
        ConnectionGuard.setLogger(getLogger());

        // 2. Copy and load configs
        File translationFolder = getDataFolder().toPath().resolve("translation").toFile();
        if (!translationFolder.exists()) {
            translationFolder.mkdirs();
        }
        boolean existingInstallation = java.nio.file.Files.exists(getDataFolder().toPath().resolve("config.yml"));
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try {
                InputStream in = ConnectionGuardBungeePlugin.class.getResourceAsStream("/config.yml");
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                getLogger().info("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(getDataFolder().toPath(), config::set);
        } catch (IOException e) {
            getLogger().info("Connection Guard | " + e.getMessage());
        }

        com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<Configuration> selectedMessages = loadMessages(config.get("message-language", null));
        languageFile = selectedMessages.file.toFile();
        languageConfig = selectedMessages.document;

        // 3. Download libraries used for vpn and geo checks
        BungeeLibraryManager libraryManager = new BungeeLibraryManager(this);

        Library bstatsLibrary = Library.builder()
                // Weird replaceAll is necessary, because the gradle shadow relocate method will
                // rewrite org.bstats to com.github.gerolndnr.connectionguard.libs.org.bstats
                // here; the literal is kept separate from the package relocation.
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-bungeecord")
                .version("3.0.2")
                .relocate("org{}bstats", "com{}github{}gerolndnr{}connectionguard{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(bstatsLibrary);

        // 4. Register specified cache provider
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
                return;
        }

        ProviderConfiguration draft = new ProviderConfiguration(path -> getConfig().get(path, null), new ArrayList<>(getConfig().getSection("provider.vpn").getKeys()), getDataFolder().toPath(), selectedMessages.messages);
        ConnectionGuard.applyProviders(draft);
        ConnectionGuard.initializeCache();
        ConnectionGuard.initializeRules(getDataFolder().toPath());
        com.github.gerolndnr.connectionguard.core.config.OperationModeNotice.show(getDataFolder().toPath(), existingInstallation, draft.settings.observe, ConnectionGuard.getLogger());
        ConnectionGuard.startTorRefresh();
        // Optional dashboard link: background only, never on the login path.
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.setReloadHook(this::reloadAllConfigs);
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.start(getDataFolder().toPath(), path -> getConfig().get(path, null), com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Platform.BUNGEE,
                getProxy().getName() + " " + getProxy().getVersion(), getDescription().getVersion(), getLogger());


        // 7. Register bungeecord listener and commands
        getProxy().getPluginManager().registerListener(this, new ConnectionGuardBungeeListener());

        getProxy().getPluginManager().registerCommand(this, new ConnectionGuardBungeeCommand());

        Metrics metrics = new Metrics(this, 22912);
    }

    @Override
    public void onDisable() {
        ConnectionGuard.shutdown();
        com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.shutdown();
        if (ConnectionGuard.getCacheProvider() != null) ConnectionGuard.getCacheProvider().disband();
    }

    public Configuration getConfig() {
        return config;
    }

    public void reloadAllConfigs() {
        synchronized (com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.reloadLock()) {
            try {
                Configuration next = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
                com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(getDataFolder().toPath(), next::set);
                com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<Configuration> selectedMessages = loadMessages(next.get("message-language", null));
                ProviderConfiguration draft = new ProviderConfiguration(path -> next.get(path, null), new ArrayList<>(next.getSection("provider.vpn").getKeys()), getDataFolder().toPath(), selectedMessages.messages);
                synchronized (ConnectionGuard.class) {
                    com.github.gerolndnr.connectionguard.core.cloud.CloudSync.validateReloadActivation();
                    ConnectionGuard.applyProviders(draft);
                    config = next;
                    languageFile = selectedMessages.file.toFile();
                    languageConfig = selectedMessages.document;
                }
            } catch (IOException invalid) { throw new IllegalArgumentException("Configuration file is invalid; active settings preserved."); }
            com.github.gerolndnr.connectionguard.core.cloud.CloudSync.refresh();
        }
    }

    public Configuration getLanguageConfig() {
        return languageConfig;
    }

    private com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<net.md_5.bungee.config.Configuration> loadMessages(Object language) {
        return com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.load(getDataFolder().toPath(), language, new com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Parser<net.md_5.bungee.config.Configuration>() {
            public net.md_5.bungee.config.Configuration parse(java.nio.file.Path file) throws Exception {
                try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    return net.md_5.bungee.config.ConfigurationProvider.getProvider(net.md_5.bungee.config.YamlConfiguration.class).load(reader);
                }
            }
            public Object value(net.md_5.bungee.config.Configuration document, String key) { return document.get(key, null); }
        });
    }

    public static ConnectionGuardBungeePlugin getInstance() {
        return connectionGuardBungeePlugin;
    }
}
