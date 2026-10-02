package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper;
import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityListener {
    private boolean needsAuthenticatedPhase() {
        return ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                || hasUuid(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions"))
                || hasUuid(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions"));
    }
    private boolean hasUuid(java.util.List<String> entries) {
        for (String entry : entries) try { UUID.fromString(entry); return true; } catch (IllegalArgumentException ignored) { }
        return false;
    }
    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed() || needsAuthenticatedPhase()) return null;
        return EventTask.withContinuation(continuation -> checkConnection(event.getConnection().getRemoteAddress().getAddress().getHostAddress(),
                null, event.getUsername(), false, null, message -> event.setResult(PreLoginEvent.PreLoginComponentResult.denied(message)))
                .whenComplete((ignored, error) -> continuation.resume()));
    }
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed() || !needsAuthenticatedPhase()) return null;
        Player player = event.getPlayer();
        return EventTask.withContinuation(continuation -> checkConnection(player.getRemoteAddress().getAddress().getHostAddress(),
                player.getUniqueId(), player.getUsername(), player.isOnlineMode() || ConnectionGuard.getSettings().trustForwardedIdentity,
                player, message -> event.setResult(ResultedEvent.ComponentResult.denied(message)))
                .whenComplete((ignored, error) -> continuation.resume()));
    }
    private CompletableFuture<Void> checkConnection(String rawIp, UUID uuid, String playerUsername, boolean trusted,
                                                     Object subject, Consumer<Component> deny) {
        final String ipAddress = Exemptions.normalize(rawIp);
        CompletableFuture<Boolean> vpnExempt = Exemptions.matches(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions"), ipAddress, uuid, trusted)
                ? CompletableFuture.completedFuture(true) : trusted && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                    ? CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn", subject) : CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> geoExempt = Exemptions.matches(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions"), ipAddress, uuid, trusted)
                ? CompletableFuture.completedFuture(true) : trusted && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                    ? CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo", subject) : CompletableFuture.completedFuture(false);
        CompletableFuture<VpnResult> vpnFuture = vpnExempt.thenCompose(exempt -> exempt
                ? CompletableFuture.completedFuture(new VpnResult(ipAddress, false)) : ConnectionGuard.getVpnResult(ipAddress));
        CompletableFuture<GeoLookup> geoFuture = geoExempt.thenCompose(exempt -> exempt
                ? CompletableFuture.completedFuture(new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0)) : ConnectionGuard.getGeoLookup(ipAddress));
        return CompletableFuture.allOf(vpnFuture, geoFuture).thenRun(() -> {
            VpnResult vpnResult = vpnFuture.join();
            Optional<GeoResult> geoResultOptional = geoFuture.join().getResult();
            if (!ConnectionGuard.getSettings().observe && (
                    (vpnResult.getStatus() == ProviderVote.Status.UNKNOWN && ConnectionGuard.getSettings().vpnFailure == GuardSettings.FailurePolicy.CLOSED)
                    || (geoFuture.join().getReason() != FailureReason.NONE && ConnectionGuard.getSettings().geoFailure == GuardSettings.FailurePolicy.CLOSED))) {
                deny.accept(Component.text("Connection verification is temporarily unavailable. Please retry shortly."));
                return;
            }
            if (vpnResult.isVpn()) {
                // Check if staff should be notified
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-notify")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", playerUsername)
                    );
                    broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                }

                // Check if command should be executed on flag
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replace("%NAME%", playerUsername)
                                    .replace("%IP%", ipAddress)
                    );
                }

                // Check if WebHook should be executed
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-webhook")
                            .replace("%NAME%", playerUsername)
                            .replace("%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-block")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", playerUsername)
                    );

                    deny.accept(kickMessage);
                    return;
                }
            }

            if (geoResultOptional.isPresent()) {
                GeoResult geoResult = geoResultOptional.get();
                boolean isGeoFlagged = false;

                switch (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.type").toLowerCase()) {
                    case "blacklist":
                        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    case "whitelist":
                        if (!ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    default:
                        ConnectionGuard.getLogger().info("Invalid geo behavior type. Please use BLACKLIST or WHITELIST.");
                        break;
                }

                if (isGeoFlagged) {
                    // Check if staff should be notified
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-notify")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", playerUsername)
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (!ConnectionGuard.getSettings().observe && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.execute-command.command")
                                        .replace("%NAME%", playerUsername)
                                        .replace("%IP%", ipAddress)
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                        );
                    }

                    // Check if WebHook should be executed
                    if (!ConnectionGuard.getSettings().observe && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-webhook")
                                .replace("%NAME%", playerUsername)
                                .replace("%IP%", ipAddress)
                                .replace("%COUNTRY%", geoResult.getCountryName())
                                .replace("%CITY%", geoResult.getCityName())
                                .replace("%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (!ConnectionGuard.getSettings().observe && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.kick-player")) {
                        Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-block")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", playerUsername)
                        );

                        deny.accept(kickMessage);
                        return;
                    }
                }

            }
        });
    }

    private void broadcastMessage(Component message, String permission) {
        for (Player player : ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getAllPlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }
}
