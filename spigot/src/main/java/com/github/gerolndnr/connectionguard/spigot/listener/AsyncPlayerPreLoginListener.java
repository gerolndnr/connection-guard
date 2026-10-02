package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper;
import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
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
        CompletableFuture<Boolean> hasVpnExemptionPermissionFuture;
        CompletableFuture<Boolean> hasGeoExemptionPermissionFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getName())
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
            hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);

            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                hasVpnExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(preLoginEvent.getUniqueId(), "connectionguard.exemption.vpn");
            } else {
                hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        if (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getName())
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
            hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);

            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                hasGeoExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(preLoginEvent.getUniqueId(), "connectionguard.exemption.geo");
            } else {
                hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture, hasVpnExemptionPermissionFuture, hasGeoExemptionPermissionFuture).join();

        VpnResult vpnResult = vpnResultFuture.join();
        Boolean hasVpnExemptionPermission = hasVpnExemptionPermissionFuture.join();
        Boolean hasGeoExemptionPermission = hasGeoExemptionPermissionFuture.join();

        if (vpnResult.isVpn() && !hasVpnExemptionPermission) {
            // Check if staff should be notified
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                ConnectionGuard.getLogger().info("staff should be notified");
                String notifyMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                .replace("%IP%", vpnResult.getIpAddress())
                                .replace("%NAME%", preLoginEvent.getName())
                );
                int amountRecipients = ConnectionGuardSpigotPlugin.getInstance().getServer().broadcast(notifyMessage, "connectionguard.notify.vpn");
                ConnectionGuard.getLogger().info("amount recipients: " + amountRecipients);
            } else {
                ConnectionGuard.getLogger().info("staff should not be notified");
            }

            // Check if command should be executed on flag
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                    @Override
                    public void run() {
                        Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                        .replace("%NAME%", preLoginEvent.getName())
                                        .replace("%IP%", ipAddress)
                        );
                    }
                });
            }

            // Check if WebHook should be executed
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-webhook")
                        .replace("%NAME%", preLoginEvent.getName())
                        .replace("%IP%", ipAddress);
                String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
            }

            // Check if player should be kicked
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                String kickMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                                .replace("%IP%", vpnResult.getIpAddress())
                                .replace("%NAME%", preLoginEvent.getName())
                );

                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                return;
            }
        }

        Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
        if (geoResultOptional.isPresent() && !hasGeoExemptionPermission) {
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
                // Check if staff should be notified
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                    String notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-notify")
                                    .replace("%IP%", geoResult.getIpAddress())
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName())
                                    .replace("%NAME%", preLoginEvent.getName())
                    );
                    Bukkit.broadcast(notifyMessage, "connectionguard.notify.geo");
                }

                // Check if command should be executed on flag
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                    Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                        @Override
                        public void run() {
                            Bukkit.dispatchCommand(
                                    Bukkit.getConsoleSender(),
                                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                            .replace("%NAME%", preLoginEvent.getName())
                                            .replace("%IP%", ipAddress)
                            );
                        }
                    });
                }

                // Check if WebHook should be executed
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-webhook")
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%IP%", ipAddress)
                            .replace("%COUNTRY%", geoResult.getCountryName())
                            .replace("%CITY%", geoResult.getCityName())
                            .replace("%ISP%", geoResult.getIspName());
                    String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
                    String kickMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-block")
                                    .replace("%IP%", geoResult.getIpAddress())
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName())
                                    .replace("%NAME%", preLoginEvent.getName())
                    );

                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                }
            }
        }
    }
}
