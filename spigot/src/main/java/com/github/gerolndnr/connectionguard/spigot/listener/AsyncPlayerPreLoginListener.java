package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class AsyncPlayerPreLoginListener implements Listener {
    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent preLoginEvent) {
        String ipAddress = preLoginEvent.getAddress().getHostAddress();

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getName())
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        }

        if (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getName())
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);
        }

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture).join();

        VpnResult vpnResult = vpnResultFuture.join();

        if (vpnResult.isVpn()) {
            String kickMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                            .replaceAll("%IP%", vpnResult.getIpAddress())
                            .replaceAll("%NAME%", preLoginEvent.getName())
            );
            String notifyMessage;

            // Check if command should be executed on flag
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.command.enabled")) {
                Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                    @Override
                    public void run() {
                        Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.command.command")
                                        .replaceAll("%NAME%", preLoginEvent.getName())
                        );
                    }
                });
            }

            switch (ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.flag").toUpperCase()) {
                case "KICK_NOTIFY":
                    notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify-kick")
                                    .replaceAll("%IP%", vpnResult.getIpAddress())
                                    .replaceAll("%NAME%", preLoginEvent.getName())
                    );
                    Bukkit.broadcast(notifyMessage, "connectionguard.notify.vpn");
                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                    break;
                case "KICK":
                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                    break;
                case "NOTIFY":
                    notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                    .replaceAll("%IP%", vpnResult.getIpAddress())
                                    .replaceAll("%NAME%", preLoginEvent.getName())
                    );
                    Bukkit.broadcast(notifyMessage, "connectionguard.notify.vpn");
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

            switch (ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.type").toLowerCase()) {
                case "blacklist":
                    if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                        isGeoFlagged = true;
                    break;
                case "whitelist":
                    if (!ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                        isGeoFlagged = true;
                    break;
                default:
                    ConnectionGuard.getLogger().info("Invalid geo behavior type. Please use BLACKLIST or WHITELIST.");
                    break;
            }

            if (isGeoFlagged) {
                String kickMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-block")
                                .replaceAll("%IP%", geoResult.getIpAddress())
                                .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                .replaceAll("%CITY%", geoResult.getCityName())
                                .replaceAll("%ISP%", geoResult.getIspName())
                                .replaceAll("%NAME%", preLoginEvent.getName())
                );
                String notifyMessage;

                // Check if command should be executed on flag
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.command.enabled")) {
                    Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                        @Override
                        public void run() {
                            Bukkit.dispatchCommand(
                                    Bukkit.getConsoleSender(),
                                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.command.command")
                                            .replaceAll("%NAME%", preLoginEvent.getName())
                            );
                        }
                    });
                }

                switch (ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.flag").toUpperCase()) {
                    case "KICK_NOTIFY":
                        notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-notify-block")
                                        .replaceAll("%IP%", geoResult.getIpAddress())
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                        .replaceAll("%CITY%", geoResult.getCityName())
                                        .replaceAll("%ISP%", geoResult.getIspName())
                                        .replaceAll("%NAME%", preLoginEvent.getName())
                        );
                        Bukkit.broadcast(notifyMessage, "connectionguard.notify.geo");
                        preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                        break;
                    case "KICK":
                        preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                        break;
                    case "NOTIFY":
                        notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-notify")
                                        .replaceAll("%IP%", geoResult.getIpAddress())
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                        .replaceAll("%CITY%", geoResult.getCityName())
                                        .replaceAll("%ISP%", geoResult.getIspName())
                                        .replaceAll("%NAME%", preLoginEvent.getName())
                        );
                        Bukkit.broadcast(notifyMessage, "connectionguard.notify.geo");
                        break;
                    case "IGNORE":
                        break;
                    default:
                        ConnectionGuard.getLogger().info("Invalid geo behavior flag. Please use KICK_NOTIFY, KICK, NOTIFY or IGNORE.");
                        break;
                }
            }
        }
    }
}
