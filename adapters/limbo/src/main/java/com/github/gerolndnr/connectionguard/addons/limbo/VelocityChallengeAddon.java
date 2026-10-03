// SPDX-License-Identifier: MIT
package com.github.gerolndnr.connectionguard.addons.limbo;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.challenge.ChallengeSession;
import com.github.gerolndnr.connectionguard.core.challenge.ChallengeSettings;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Properties;

/** Bootstrap intentionally has no native SDK signatures, so missing SDK cannot disable the closed gate. */
@Plugin(id="connection-guard-limbo", name="Connection Guard Challenge", version="0.1.0-dev",
        dependencies={@Dependency(id="connection-guard"), @Dependency(id="limboapi", optional=true)})
public final class VelocityChallengeAddon {
    interface Transport extends AutoCloseable { @Override void close(); }
    private final ProxyServer server;
    private final Logger logger;
    private final Path directory;
    private final IdentityHashMap<Player, ChallengeSession> sessions = new IdentityHashMap<>();
    private volatile boolean live = true;
    private volatile boolean enabled = true; // Invalid configuration must not turn the gate off.
    private ChallengeSettings settings;
    private Transport transport;
    @Inject public VelocityChallengeAddon(ProxyServer server, Logger logger, @DataDirectory Path directory) {
        this.server=server; this.logger=logger; this.directory=directory;
    }
    @Subscribe(order=PostOrder.LAST) public void initialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(directory);
            Path file=directory.resolve("challenge.properties");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                try (InputStream input=getClass().getResourceAsStream("/challenge.properties")) {
                    if (input==null) throw new IOException("Missing challenge defaults");
                    Files.copy(input, file);
                }
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)>8192)
                throw new IOException("Challenge configuration must be a bounded regular file");
            Properties values=new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) throw new IllegalArgumentException("Duplicate challenge configuration key");
                    return super.put(key, value);
                }
            };
            try (InputStream input=Files.newInputStream(file)) { values.load(input); }
            settings=ChallengeSettings.read(values); enabled=settings.enabled;
            if (!enabled) { logger.info("Connection Guard challenge disabled."); return; }
            PluginContainer container=server.getPluginManager().getPlugin("limboapi").orElse(null);
            Object nativePlugin=container==null ? null : container.getInstance().orElse(null);
            boolean selected=nativePlugin!=null && nativePlugin.getClass().getName().equals("net.elytrium.limboapi.LimboAPI")
                    && container.getDescription().getVersion().filter("1.1.27-SNAPSHOT (git-e638f4d)"::equals).isPresent();
            if (!selected) { logger.error("Connection Guard challenge unavailable: install the documented native SDK. Gate remains closed."); return; }
            transport=new NativeLimboTransport(this, nativePlugin, server, settings);
            logger.info("Connection Guard challenge enabled; no identity or access exemption is granted by passing.");
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            logger.error("Connection Guard challenge initialization failed ({}); gate remains closed.", unavailable.getClass().getSimpleName());
        }
    }
    boolean required() { return enabled && !ConnectionGuard.getSettings().observe; }
    synchronized ChallengeSession begin(Player player) {
        if (!live || settings==null || !required() || sessions.containsKey(player)
                || sessions.size()>=settings.maximumSessions) return null;
        GuardSettings policy=ConnectionGuard.getSettings();
        InetSocketAddress address=player.getRemoteAddress();
        if (address==null || address.isUnresolved()) return null;
        ChallengeSession session=new ChallengeSession(player,
                () -> live && player.isActive() && address.equals(player.getRemoteAddress())
                        && ConnectionGuard.getSettings()==policy,
                settings.timeoutSeconds*1000L, settings.maximumAttempts);
        sessions.put(player, session);
        return session;
    }
    synchronized boolean passed(Player player) {
        ChallengeSession session=sessions.get(player);
        return live && session!=null && session.isPassed(player);
    }
    synchronized boolean forget(Player player, ChallengeSession expected) {
        if (sessions.get(player)!=expected) return false;
        sessions.remove(player); expected.close(); return true;
    }
    private synchronized void forget(Player player) {
        ChallengeSession session=sessions.remove(player); if (session!=null) session.close();
    }
    static void refuse(Player player) { player.disconnect(Component.text("Connection verification failed or is unavailable. Please retry.")); }
    @Subscribe(order=PostOrder.LAST) public void login(LoginEvent event) {
        if (required() && event.getResult().isAllowed() && !passed(event.getPlayer()))
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text("Connection verification is required before server access.")));
    }
    @Subscribe(order=PostOrder.LAST) public void backend(ServerPreConnectEvent event) {
        // Initial backend contact is separately guarded even if another plugin advances the native queue.
        if (required() && event.getPreviousServer()==null && !passed(event.getPlayer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied()); refuse(event.getPlayer());
        }
    }
    @Subscribe public void connected(ServerConnectedEvent event) { forget(event.getPlayer()); }
    @Subscribe public void disconnected(DisconnectEvent event) { forget(event.getPlayer()); }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        live=false;
        if (transport!=null) { transport.close(); transport=null; }
        synchronized (this) {
            for (java.util.Map.Entry<Player, ChallengeSession> entry : sessions.entrySet()) {
                entry.getValue().close(); refuse(entry.getKey());
            }
            sessions.clear();
        }
    }
}
