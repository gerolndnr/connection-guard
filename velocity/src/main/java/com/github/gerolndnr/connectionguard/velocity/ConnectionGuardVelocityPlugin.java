package com.github.gerolndnr.connectionguard.velocity;

import com.alessiodp.libby.Library;
import com.alessiodp.libby.VelocityLibraryManager;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.IpApiGeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.velocity.listener.ConnectionGuardVelocityListener;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

@Plugin(
        id="connection-guard",
        name="Connection Guard",
        version="0.1.0",
        url="https://github.com/gerolndnr/connection-guard",
        authors = {"gerolndnr"}
)
public class ConnectionGuardVelocityPlugin {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private File configFile;
    private File languageFile;
    private YamlDocument config;
    private YamlDocument languageConfig;

    @Inject
    public ConnectionGuardVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent initializeEvent) {
        // 1. Set logger
        ConnectionGuard.setLogger(java.util.logging.Logger.getLogger(logger.getName()));

        // 2. Download libraries used for vpn and geo checks and config
        VelocityLibraryManager libraryManager = new VelocityLibraryManager(this, logger, dataDirectory, proxyServer.getPluginManager());
        Library boostedYamlLibrary = Library.builder()
                .groupId("dev.defvokep")
                .artifactId("boosted-yaml")
                .version("1.3.5")
                .resolveTransitiveDependencies(true)
                .build();
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
        libraryManager.loadLibrary(boostedYamlLibrary);
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);

        // 3. Create and load configs
        File translationFolder = dataDirectory.resolve("translation").toFile();
        if (!translationFolder.exists()) {
            translationFolder.mkdirs();
        }
        configFile = new File(dataDirectory.toFile(), "config.yml");
        if (!configFile.exists()) {
            try {
                InputStream in = getClass().getResourceAsStream("config.yml");
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                logger.error("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            config = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(true).build());
        } catch (IOException e) {
            logger.error("Connection Guard | " + e.getMessage());
        }

        String selectedLanguageFileName = config.getString("message-language") + ".yml";
        languageFile = new File(dataDirectory.resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            try {
                InputStream in = getClass().getResourceAsStream("translation" + File.separator + "en.yml");
                Files.copy(in, languageFile.toPath());
            } catch (IOException e) {
                logger.error("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            languageConfig = YamlDocument.create(languageFile, GeneralSettings.builder().setUseDefaults(true).build());
        } catch (IOException e) {
            logger.error("Connection Guard | " + e.getMessage());
            return;
        }

        // 4. Register specified cache provider
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
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(dataDirectory.toFile(), "cache.db").getAbsolutePath()));
                break;
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                logger.error("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
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
                logger.info("The specified geo provider is invalid. Please use IP-API.");
        }

        // 6. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(getConfig().getInt("provider.cache.expiration.geo"));

        // 7. Register velocity listener
        proxyServer.getEventManager().register(this, new ConnectionGuardVelocityListener());
    }

    public YamlDocument getConfig() {
        return config;
    }

    public YamlDocument getLanguageConfig() {
        return languageConfig;
    }
}
