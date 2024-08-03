package com.github.gerolndnr.connectionguard.bungee;

import com.alessiodp.libby.BungeeLibraryManager;
import com.alessiodp.libby.Library;
import com.github.gerolndnr.connectionguard.bungee.listener.ConnectionGuardBungeeListener;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.IpApiGeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;

public class ConnectionGuardBungeePlugin extends Plugin {
    private static ConnectionGuardBungeePlugin connectionGuardBungeePlugin;
    private Configuration config;
    private Configuration languageConfig;

    @Override
    public void onEnable() {
        connectionGuardBungeePlugin = this;

        // 1. Set logger
        ConnectionGuard.setLogger(getLogger());

        // 2. Copy and load configs
        File translationFolder = getDataFolder().toPath().resolve("translation").toFile();
        if (!translationFolder.exists()) {
            translationFolder.mkdirs();
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try {
                InputStream in = getResourceAsStream("config.yml");
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
        File languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            try {
                InputStream in = getResourceAsStream("translation" + File.separator + "en.yml");
                Files.copy(in, languageFile.toPath());
            } catch (IOException e) {
                getLogger().info("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            languageConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
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
                .build();
        // Library sqliteLibrary = Library.builder()
        //         .groupId("org.xerial")
        //         .artifactId("sqlite-jdbc")
        //         .version("3.46.0.0")
        //         .resolveTransitiveDependencies(true)
        //         .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        // libraryManager.loadLibrary(sqliteLibrary);

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
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                getLogger().info("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
                return;
        }

        ConnectionGuard.getCacheProvider().setup();

        // 5. Add every enabled vpn provider and geo provider
        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();

        if (getConfig().getBoolean("provider.vpn.proxycheck.enabled"))
            vpnProviders.add(new ProxyCheckVpnProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));

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

        // 6. Register bungeecord listener
        getProxy().getPluginManager().registerListener(this, new ConnectionGuardBungeeListener());
    }

    @Override
    public void onDisable() {
        ConnectionGuard.getCacheProvider().disband();
    }

    public Configuration getConfig() {
        return config;
    }

    public Configuration getLanguageConfig() {
        return languageConfig;
    }

    public static ConnectionGuardBungeePlugin getInstance() {
        return connectionGuardBungeePlugin;
    }
}
