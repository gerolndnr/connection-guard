package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityListener {
    @Subscribe
    public EventTask onPreLogin(LoginEvent loginEvent) {
        String ipAddress = loginEvent.getPlayer().getRemoteAddress().getAddress().getHostAddress();

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getPlayer().getUniqueId().toString())
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getPlayer().getUsername())
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        }

        if (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getPlayer().getUniqueId().toString())
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getPlayer().getUsername())
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);
        }

        return EventTask.async(() -> {
            VpnResult vpnResult = vpnResultFuture.join();

            if (vpnResult.isVpn()) {
                Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-block")
                                .replaceAll("%IP%", vpnResult.getIpAddress())
                                .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                );
                Component notifyMessage;

                // Check if command should be executed on flag
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.command.enabled")) {
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.command.command")
                                    .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                    );
                }

                switch (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.flag").toUpperCase()) {
                    case "KICK_NOTIFY":
                        notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-notify-kick")
                                        .replaceAll("%IP%", vpnResult.getIpAddress())
                                        .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.vpn");

                        loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                        return;
                    case "KICK":
                        loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                        return;
                    case "NOTIFY":
                        notifyMessage = LegacyComponentSerializer.legacySection().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-notify")
                                        .replaceAll("%IP%", vpnResult.getIpAddress())
                                        .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                        break;
                    case "IGNORE":
                        break;
                    default:
                        ConnectionGuard.getLogger().info("Invalid vpn behaviour flag. Please use KICK_NOTIFY, KICK, NOTIFY or IGNORE.");
                        break;
                }
            }

            Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
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
                    Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-block")
                                    .replaceAll("%IP%", geoResult.getIpAddress())
                                    .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                    .replaceAll("%CITY%", geoResult.getCityName())
                                    .replaceAll("%ISP%", geoResult.getIspName())
                                    .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                    );
                    Component notifyMessage;

                    // Check if command should be executed on flag
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.command.enabled")) {
                        ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.command.command")
                                        .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                        );
                    }

                    switch (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.flag").toUpperCase()) {
                        case "KICK_NOTIFY":
                            notifyMessage = LegacyComponentSerializer.legacySection().deserialize(
                                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-notify-block")
                                            .replaceAll("%IP%", geoResult.getIpAddress())
                                            .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                            .replaceAll("%CITY%", geoResult.getCityName())
                                            .replaceAll("%ISP%", geoResult.getIspName())
                                            .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                            );
                            broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                            loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                            return;
                        case "KICK":
                            loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                            return;
                        case "NOTIFY":
                            notifyMessage = LegacyComponentSerializer.legacySection().deserialize(
                                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-notify")
                                            .replaceAll("%IP%", geoResult.getIpAddress())
                                            .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                            .replaceAll("%CITY%", geoResult.getCityName())
                                            .replaceAll("%ISP%", geoResult.getIspName())
                                            .replaceAll("%NAME%", loginEvent.getPlayer().getUsername())
                            );
                            broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                            break;
                        case "IGNORE":
                            break;
                        default:
                            ConnectionGuard.getLogger().info("Invalid geo behavior flag. Please use KICK_NOTIFY, KICK, NOTIFY or IGNORE.");
                            break;
                    }
                }

                loginEvent.setResult(ResultedEvent.ComponentResult.allowed());
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
