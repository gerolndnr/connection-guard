package com.github.gerolndnr.connectionguard.velocity;

import net.byteflux.libby.Library;
import net.byteflux.libby.VelocityLibraryManager;
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
import org.bstats.velocity.Metrics;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

@Plugin(
        id="connection-guard",
        name="Connection Guard",
        version=BuildVersion.VERSION,
        url="https://connectionguard.net",
        authors = {"gerolndnr"},
        dependencies = {@com.velocitypowered.api.plugin.Dependency(id="floodgate", optional=true)}
)
public class ConnectionGuardVelocityPlugin {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private Metrics metrics;
    // Config has to be in an external class, because the YAML library is loaded at runtime.
    private CGVelocityConfig cgVelocityConfig;
    private static ConnectionGuardVelocityPlugin connectionGuardVelocityPlugin;
    private HashMap<String, VpnProvider> vpnProviderMap;

    @Inject
    public ConnectionGuardVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
        this.vpnProviderMap = new HashMap<>();

        connectionGuardVelocityPlugin = this;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent initializeEvent) {
        // 1. Set logger
        ConnectionGuard.setLogger(java.util.logging.Logger.getLogger(logger.getName()));

        // 2. Download libraries used for vpn and geo checks and config
        VelocityLibraryManager<ConnectionGuardVelocityPlugin> libraryManager = new VelocityLibraryManager<>(logger, dataDirectory, proxyServer.getPluginManager(), this);
        Library boostedYamlLibrary = Library.builder()
                .groupId("dev.dejvokep")
                .artifactId("boosted-yaml")
                .version("1.3.6")
                .relocate("dev.defvokep.boostedyaml", "com.github.gerolndnr.connectionguard.libs.dev.defvokep.boostedyaml")
                .build();
        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(boostedYamlLibrary);

        // 3. Create and load configs
        boolean existingInstallation = java.nio.file.Files.exists(dataDirectory.resolve("config.yml"));
        cgVelocityConfig = new CGVelocityConfig(dataDirectory);
        cgVelocityConfig.load();

        // 4. Register specified cache provider
        switch (cgVelocityConfig.getConfig().getString("provider.cache.type").toLowerCase()) {
            case "sqlite":
                Library sqliteLibrary = Library.builder()
                        .groupId("org.xerial")
                        .artifactId("sqlite-jdbc")
                        .version("3.46.0.0")
                        .build();
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(dataDirectory.toFile(), "cache.db").getAbsolutePath()));
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
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.hostname"),
                                getCgVelocityConfig().getConfig().getInt("provider.cache.redis.port"),
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.username"),
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.password"),
                                GuardSettings.bool(path -> getCgVelocityConfig().getConfig().get(path), "provider.cache.redis.tls", false)
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
                logger.error("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
                return;
        }

        ProviderConfiguration draft = new ProviderConfiguration(path -> getCgVelocityConfig().getConfig().get(path), getCgVelocityConfig().getConfig().getSection("provider.vpn").getKeys().stream().map(Object::toString).collect(java.util.stream.Collectors.toList()), dataDirectory, cgVelocityConfig.getMessages());
        ConnectionGuard.applyProviders(draft);
        ConnectionGuard.initializeCache();
        ConnectionGuard.initializeRules(dataDirectory);
        com.github.gerolndnr.connectionguard.core.config.OperationModeNotice.show(dataDirectory, existingInstallation, draft.settings.observe, ConnectionGuard.getLogger());
        ConnectionGuard.startTorRefresh();
        // Optional dashboard link: background only, never on the login path.
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.setReloadHook(() -> {
            try { getCgVelocityConfig().reloadValidated(); }
            catch (java.io.IOException unreadable) { throw new IllegalArgumentException("config.yml could not be read; active settings preserved."); }
        });
        String pluginVersion = proxyServer.getPluginManager().fromInstance(this)
                .flatMap(container -> container.getDescription().getVersion()).orElse("unknown");
        com.github.gerolndnr.connectionguard.core.cloud.CloudSync.start(dataDirectory, path -> getCgVelocityConfig().getConfig().get(path), com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Platform.VELOCITY,
                proxyServer.getVersion().getName() + " " + proxyServer.getVersion().getVersion(), pluginVersion, ConnectionGuard.getLogger());


        // 7. Register velocity listener and commands
        proxyServer.getEventManager().register(this, new ConnectionGuardVelocityListener());

        CommandMeta commandMeta = proxyServer.getCommandManager().metaBuilder("connectionguard")
                .aliases("cg")
                .plugin(this)
                .build();
        SimpleCommand simpleCommand = new ConnectionGuardVelocityCommand();
        proxyServer.getCommandManager().register(commandMeta, simpleCommand);
        // Platform-standard bStats opt-out applies; no player identities or custom data are added.
        try { metrics = metricsFactory.make(this, 22913); }
        catch (RuntimeException | LinkageError unavailable) {
            logger.warn("bStats initialization failed; connection checks remain active.");
        }
    }

    @Subscribe
    public void onProxyShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        if (metrics != null) {
            try { metrics.shutdown(); }
            catch (RuntimeException | LinkageError unavailable) { logger.warn("bStats shutdown failed; continuing plugin shutdown."); }
        }
        ConnectionGuard.shutdown();
        com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.shutdown();
        if (ConnectionGuard.getCacheProvider() != null) ConnectionGuard.getCacheProvider().disband();
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
