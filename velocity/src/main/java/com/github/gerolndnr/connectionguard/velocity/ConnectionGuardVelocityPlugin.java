package com.github.gerolndnr.connectionguard.velocity;

import com.alessiodp.libby.Library;
import com.alessiodp.libby.VelocityLibraryManager;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.RedisCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.SQLiteCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.IpApiGeoProvider;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.vpn.custom.CustomVpnProvider;
import com.github.gerolndnr.connectionguard.velocity.commands.ConnectionGuardVelocityCommand;
import com.github.gerolndnr.connectionguard.velocity.listener.ConnectionGuardVelocityListener;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;

@Plugin(
        id="connection-guard",
        name="Connection Guard",
        version="0.2.0",
        url="https://github.com/gerolndnr/connection-guard",
        authors = {"gerolndnr"}
)
public class ConnectionGuardVelocityPlugin {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    // Config has to be in an external class, because the YAML library is loaded at runtime.
    private CGVelocityConfig cgVelocityConfig;
    private static ConnectionGuardVelocityPlugin connectionGuardVelocityPlugin;


    @Inject
    public ConnectionGuardVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        connectionGuardVelocityPlugin = this;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent initializeEvent) {
        // 1. Set logger
        ConnectionGuard.setLogger(java.util.logging.Logger.getLogger(logger.getName()));

        // 2. Download libraries used for vpn and geo checks and config
        VelocityLibraryManager libraryManager = new VelocityLibraryManager(this, logger, dataDirectory, proxyServer.getPluginManager());
        Library boostedYamlLibrary = Library.builder()
                .groupId("dev.dejvokep")
                .artifactId("boosted-yaml")
                .version("1.3.6")
                .resolveTransitiveDependencies(true)
                .relocate("dev.defvokep.boostedyaml", "com.github.gerolndnr.connectionguard.libs.dev.defvokep.boostedyaml")
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
                .relocate("com{}google{}gson", "com{}github{}gerolndnr{}connectionguard{}libs{}com{}google{}gson")
                .build();
        Library bstatsLibrary = Library.builder()
                // Weird replaceAll is necessary, because the gradle shadow relocate method will
                // rewrite org.bstats to com.github.gerolndnr.connectionguard.libs.org.bstats
                // here, but not for libraries like gson.
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-velocity")
                .version("3.0.2")
                .resolveTransitiveDependencies(true)
                .relocate("org{}bstats", "com{}github{}gerolndnr{}connectionguard{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(boostedYamlLibrary);
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        libraryManager.loadLibrary(bstatsLibrary);

        // 3. Create and load configs
        cgVelocityConfig = new CGVelocityConfig(dataDirectory);
        cgVelocityConfig.load();

        // 4. Register specified cache provider
        switch (cgVelocityConfig.getConfig().getString("provider.cache.type").toLowerCase()) {
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
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.hostname"),
                                getCgVelocityConfig().getConfig().getInt("provider.cache.redis.port"),
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.username"),
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.password")
                        )
                );
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

        if (cgVelocityConfig.getConfig().getBoolean("provider.vpn.proxycheck.enabled"))
            vpnProviders.add(new ProxyCheckVpnProvider(cgVelocityConfig.getConfig().getString("provider.vpn.proxycheck.api-key")));
        if (cgVelocityConfig.getConfig().getBoolean("provider.vpn.ip-api.enabled"))
            vpnProviders.add(new IpApiVpnProvider());
        if (cgVelocityConfig.getConfig().getBoolean("provider.vpn.iphub.enabled"))
            vpnProviders.add(new IpHubVpnProvider(cgVelocityConfig.getConfig().getString("provider.vpn.iphub.api-key")));
        if (cgVelocityConfig.getConfig().getBoolean("provider.vpn.vpnapi.enabled"))
            vpnProviders.add(new VpnApiVpnProvider(cgVelocityConfig.getConfig().getString("provider.vpn.vpnapi.api-key")));
        if (getCgVelocityConfig().getConfig().getBoolean("provider.vpn.custom.enabled")) {
            vpnProviders.add(
                    new CustomVpnProvider(
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.request-type"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.request-url"),
                            getCgVelocityConfig().getConfig().getStringList("provider.vpn.custom.request-header"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.request-body-type"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.request-body"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.response-type"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.response-format.is-vpn-field.field-name"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.response-format.is-vpn-field.field-type"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.response-format.is-vpn-field.string-options.is-vpn-string"),
                            getCgVelocityConfig().getConfig().getString("provider.vpn.custom.response-format.vpn-provider-field.field-name")
                    )
            );
        }


        ConnectionGuard.setVpnProviders(vpnProviders);

        switch (cgVelocityConfig.getConfig().getString("provider.geo.service").toLowerCase()) {
            case "ip-api":
                ConnectionGuard.setGeoProvider(new IpApiGeoProvider());
                break;
            default:
                logger.info("The specified geo provider is invalid. Please use IP-API.");
        }

        // 6. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(cgVelocityConfig.getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(cgVelocityConfig.getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(cgVelocityConfig.getConfig().getInt("provider.cache.expiration.geo"));

        // 7. Register velocity listener and commands
        proxyServer.getEventManager().register(this, new ConnectionGuardVelocityListener());

        CommandMeta commandMeta = proxyServer.getCommandManager().metaBuilder("connectionguard")
                .aliases("cg")
                .plugin(this)
                .build();
        SimpleCommand simpleCommand = new ConnectionGuardVelocityCommand();
        proxyServer.getCommandManager().register(commandMeta, simpleCommand);
    }

    public Logger getLogger() {
        return logger;
    }

    public CGVelocityConfig getCgVelocityConfig() {
        return cgVelocityConfig;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public static ConnectionGuardVelocityPlugin getInstance() {
        return connectionGuardVelocityPlugin;
    }
}
