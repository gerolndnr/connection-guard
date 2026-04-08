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
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityListener {
    @Subscribe
    public EventTask onPreLogin(PreLoginEvent loginEvent) {
        String ipAddress = loginEvent.getConnection().getRemoteAddress().getHostString();
        String playerUuid = (loginEvent.getUniqueId() != null) ? loginEvent.getUniqueId().toString() : "";
        String playerUsername = loginEvent.getUsername();

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(playerUuid)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(playerUsername)
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        }

        if (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(playerUuid)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(playerUsername)
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);
        }

        return EventTask.async(() -> {
            VpnResult vpnResult = vpnResultFuture.join();
            Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();

            // Check if player has a permission exemption
            if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                if (CGLuckPermsHelper.hasPermission(loginEvent.getUniqueId(), "connectionguard.exemption.vpn").join()) {
                    vpnResult = new VpnResult(ipAddress, false);
                }
            }

            if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                if (CGLuckPermsHelper.hasPermission(loginEvent.getUniqueId(), "connectionguard.exemption.geo").join()) {
                    geoResultOptional = Optional.empty();
                }
            }



            if (vpnResult.isVpn()) {
                // Check if staff should be notified
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-notify")
                                    .replaceAll("%IP%", vpnResult.getIpAddress())
                                    .replaceAll("%NAME%", playerUsername)
                    );
                    broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                }

                // Check if command should be executed on flag
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replaceAll("%NAME%", playerUsername)
                                    .replaceAll("%IP%", ipAddress)
                    );
                }

                // Check if WebHook should be executed
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-webhook")
                            .replaceAll("%NAME%", playerUsername)
                            .replaceAll("%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-block")
                                    .replaceAll("%IP%", vpnResult.getIpAddress())
                                    .replaceAll("%NAME%", playerUsername)
                    );

                    // loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                    loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
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
                        Component notifyMessage = LegacyComponentSerializer.legacySection().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-notify")
                                        .replaceAll("%IP%", geoResult.getIpAddress())
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                        .replaceAll("%CITY%", geoResult.getCityName())
                                        .replaceAll("%ISP%", geoResult.getIspName())
                                        .replaceAll("%NAME%", playerUsername)
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.execute-command.command")
                                        .replaceAll("%NAME%", playerUsername)
                                        .replaceAll("%IP%", ipAddress)
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                        );
                    }

                    // Check if WebHook should be executed
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-webhook")
                                .replaceAll("%NAME%", playerUsername)
                                .replaceAll("%IP%", ipAddress)
                                .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                .replaceAll("%CITY%", geoResult.getCityName())
                                .replaceAll("%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.kick-player")) {
                        Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-block")
                                        .replaceAll("%IP%", geoResult.getIpAddress())
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                        .replaceAll("%CITY%", geoResult.getCityName())
                                        .replaceAll("%ISP%", geoResult.getIspName())
                                        .replaceAll("%NAME%", playerUsername)
                        );

                        // loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                        loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
                        return;
                    }
                }

                // loginEvent.setResult(ResultedEvent.ComponentResult.allowed());
                loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
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
