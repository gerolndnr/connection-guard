package com.github.gerolndnr.connectionguard.bungee;

import com.alessiodp.libby.BungeeLibraryManager;
import com.alessiodp.libby.Library;
import com.github.gerolndnr.connectionguard.bungee.commands.ConnectionGuardBungeeCommand;
import com.github.gerolndnr.connectionguard.bungee.listener.ConnectionGuardBungeeListener;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.RedisCacheProvider;
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
    private Configuration config;
    private Configuration languageConfig;

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
        } catch (IOException e) {
            getLogger().info("Connection Guard | " + e.getMessage());
        }

        String selectedLanguageFileName = config.getString("message-language") + ".yml";
        languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            try {
                InputStream in = ConnectionGuardBungeePlugin.class.getResourceAsStream("/translation/en.yml");
                Files.copy(in, languageFile.toPath());
            } catch (IOException e) {
                getLogger().info("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            languageConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(languageFile);
        } catch (IOException e) {
            getLogger().info("Connection Guard | " + e.getMessage());
            return;
        }


        // 3. Download libraries used for vpn and geo checks
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
                .relocate("com{}google{}gson", "com{}github{}gerolndnr{}connectionguard{}libs{}com{}google{}gson")
                .build();
        Library bstatsLibrary = Library.builder()
                // Weird replaceAll is necessary, because the gradle shadow relocate method will
                // rewrite org.bstats to com.github.gerolndnr.connectionguard.libs.org.bstats
                // here, but not for libraries like gson.
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-bungeecord")
                .version("3.0.2")
                .resolveTransitiveDependencies(true)
                .relocate("org{}bstats", "com{}github{}gerolndnr{}connectionguard{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        libraryManager.loadLibrary(bstatsLibrary);

        // 4. Register specified cache provider
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
                return;
        }

        ConnectionGuard.getCacheProvider().setup();

        // 5. Add every enabled vpn provider and geo provider
        vpnProviderMap.put("proxycheck", new ProxyCheckVpnProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));
        vpnProviderMap.put("ip-api", new IpApiVpnProvider());
        vpnProviderMap.put("iphub", new IpHubVpnProvider(getConfig().getString("provider.vpn.iphub.api-key")));
        vpnProviderMap.put("vpnapi", new VpnApiVpnProvider(getConfig().getString("provider.vpn.vpnapi.api-key")));

        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();

        for (String key : getConfig().getSection("provider.vpn").getKeys()) {
            if (getConfig().getBoolean("provider.vpn." + key + ".enabled")) {
                if (vpnProviderMap.get(key) != null) {
                    vpnProviders.add(vpnProviderMap.get(key));
                } else {
                    vpnProviders.add(
                            new CustomVpnProvider(
                                    getConfig().getString("provider.vpn." + key + ".request-type"),
                                    getConfig().getString("provider.vpn." + key + ".request-url"),
                                    getConfig().getStringList("provider.vpn." + key + ".request-header"),
                                    getConfig().getString("provider.vpn." + key + ".request-body-type"),
                                    getConfig().getString("provider.vpn." + key + ".request-body"),
                                    getConfig().getString("provider.vpn." + key + ".response-type"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.field-name"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.field-type"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.string-options.is-vpn-string"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.vpn-provider-field.field-name")
                            )
                    );
                }
                ConnectionGuard.getLogger().info("Registered vpn detection provider '" + key + "'.");
            }
        }

        ConnectionGuard.setVpnProviders(vpnProviders);

        switch (getConfig().getString("provider.geo.service").toLowerCase()) {
            case "ip-api":
                ConnectionGuard.setGeoProvider(new IpApiGeoProvider());
                break;
            case "proxycheck":
                ConnectionGuard.setGeoProvider(new ProxyCheckGeoProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));
                break;
            default:
                getLogger().info("The specified geo provider is invalid. Please use IP-API.");
        }

        // 6. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(getConfig().getInt("provider.cache.expiration.geo"));

        // 7. Register bungeecord listener and commands
        getProxy().getPluginManager().registerListener(this, new ConnectionGuardBungeeListener());

        getProxy().getPluginManager().registerCommand(this, new ConnectionGuardBungeeCommand());

        Metrics metrics = new Metrics(this, 22912);
    }

    @Override
    public void onDisable() {
        ConnectionGuard.getCacheProvider().disband();
    }

    public Configuration getConfig() {
        return config;
    }

    public void reloadAllConfigs() {
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
        } catch (IOException e) {
            getLogger().info("Connection Guard | " + e.getMessage());
        }
        try {
            languageConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(languageFile);
        } catch (IOException e) {
            getLogger().info("Connection Guard | " + e.getMessage());
        }
    }

    public Configuration getLanguageConfig() {
        return languageConfig;
    }

    public static ConnectionGuardBungeePlugin getInstance() {
        return connectionGuardBungeePlugin;
    }
}
