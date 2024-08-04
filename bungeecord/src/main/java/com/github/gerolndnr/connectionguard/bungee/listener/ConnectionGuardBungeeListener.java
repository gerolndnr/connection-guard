package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.bungee.ConnectionGuardBungeePlugin;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    @EventHandler
    public void onLogin(LoginEvent loginEvent) {
        loginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String ipAddress = loginEvent.getConnection().getAddress().getAddress().getHostAddress();

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getConnection().getUniqueId().toString())
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getConnection().getName())
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        }

        if (
                ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getConnection().getUniqueId().toString())
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getConnection().getName())
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);
        }

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture).thenRun(() -> {
            VpnResult vpnResult = vpnResultFuture.join();

            if (vpnResult.isVpn()) {
                String kickMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                                .replaceAll("%IP%", vpnResult.getIpAddress())
                                .replaceAll("%NAME%", loginEvent.getConnection().getName())
                );
                String notifyMessage;

                switch (ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.flag").toUpperCase()) {
                    case "KICK_NOTIFY":
                        notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify-kick")
                                        .replaceAll("%IP%", vpnResult.getIpAddress())
                                        .replaceAll("%NAME%", loginEvent.getConnection().getName())
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                        loginEvent.setCancelReason(new TextComponent(kickMessage));
                        loginEvent.setCancelled(true);
                        break;
                    case "KICK":
                        loginEvent.setCancelReason(new TextComponent(kickMessage));
                        loginEvent.setCancelled(true);
                        break;
                    case "NOTIFY":
                        notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                        .replaceAll("%IP%", vpnResult.getIpAddress())
                                        .replaceAll("%NAME%", loginEvent.getConnection().getName())
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

                switch (ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.type").toLowerCase()) {
                    case "blacklist":
                        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    case "whitelist":
                        if (!ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    default:
                        ConnectionGuard.getLogger().info("Invalid geo behavior type. Please use BLACKLIST or WHITELIST.");
                        break;
                }

                if (isGeoFlagged) {
                    String kickMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-block")
                                    .replaceAll("%IP%", geoResult.getIpAddress())
                                    .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                    .replaceAll("%CITY%", geoResult.getCityName())
                                    .replaceAll("%ISP%", geoResult.getIspName())
                                    .replaceAll("%NAME%", loginEvent.getConnection().getName())
                    );
                    String notifyMessage;

                    switch (ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.flag").toUpperCase()) {
                        case "KICK_NOTIFY":
                            notifyMessage = ChatColor.translateAlternateColorCodes(
                                    '&',
                                    ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-notify-block")
                                            .replaceAll("%IP%", geoResult.getIpAddress())
                                            .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                            .replaceAll("%CITY%", geoResult.getCityName())
                                            .replaceAll("%ISP%", geoResult.getIspName())
                                            .replaceAll("%NAME%", loginEvent.getConnection().getName())
                            );
                            broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                            loginEvent.setCancelReason(new TextComponent(kickMessage));
                            loginEvent.setCancelled(true);
                            break;
                        case "KICK":
                            loginEvent.setCancelReason(new TextComponent(kickMessage));
                            loginEvent.setCancelled(true);
                            break;
                        case "NOTIFY":
                            notifyMessage = ChatColor.translateAlternateColorCodes(
                                    '&',
                                    ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-notify")
                                            .replaceAll("%IP%", geoResult.getIpAddress())
                                            .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                            .replaceAll("%CITY%", geoResult.getCityName())
                                            .replaceAll("%ISP%", geoResult.getIspName())
                                            .replaceAll("%NAME%", loginEvent.getConnection().getName())
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
            }

            loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
        });


    }

    private void broadcastMessage(String message, String permission) {
        for (ProxiedPlayer proxiedPlayer : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
            if (proxiedPlayer.hasPermission(permission)) {
                proxiedPlayer.sendMessage(TextComponent.fromLegacyText(message));
            }
        }
    }
}
