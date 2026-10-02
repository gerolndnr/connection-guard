package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.bungee.ConnectionGuardBungeePlugin;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Optional;
import java.util.UUID;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    @EventHandler
    public void onLogin(LoginEvent loginEvent) {
        loginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String rawIp = loginEvent.getConnection().getAddress().getAddress().getHostAddress();

        final String ipAddress = Exemptions.normalize(rawIp);
        final String clientIp = ipAddress;
        UUID uuid = loginEvent.getConnection().getUniqueId();
        boolean trusted = loginEvent.getConnection().isOnlineMode() || ConnectionGuard.getSettings().trustForwardedIdentity;
        CompletableFuture<Boolean> hasVpnExemptionPermissionFuture = Exemptions.matches(ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions"), clientIp, uuid, trusted)
                ? CompletableFuture.completedFuture(true) : trusted && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                    ? CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn") : CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> hasGeoExemptionPermissionFuture = Exemptions.matches(ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions"), clientIp, uuid, trusted)
                ? CompletableFuture.completedFuture(true) : trusted && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                    ? CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo") : CompletableFuture.completedFuture(false);
        CompletableFuture<VpnResult> vpnResultFuture = hasVpnExemptionPermissionFuture.thenCompose(exempt -> exempt
                ? CompletableFuture.completedFuture(new VpnResult(clientIp, false)) : ConnectionGuard.getVpnResult(clientIp));
        CompletableFuture<GeoLookup> geoLookupFuture = hasGeoExemptionPermissionFuture.thenCompose(exempt -> exempt
                ? CompletableFuture.completedFuture(new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0)) : ConnectionGuard.getGeoLookup(clientIp));
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture = geoLookupFuture.thenApply(GeoLookup::getResult);

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture, hasVpnExemptionPermissionFuture, hasGeoExemptionPermissionFuture).thenRun(() -> {
            VpnResult vpnResult = vpnResultFuture.join();
            Boolean hasVpnExemption = hasVpnExemptionPermissionFuture.join();
            Boolean hasGeoExemption = hasGeoExemptionPermissionFuture.join();


            if (!ConnectionGuard.getSettings().observe && (
                    (vpnResult.getStatus() == ProviderVote.Status.UNKNOWN && ConnectionGuard.getSettings().vpnFailure == GuardSettings.FailurePolicy.CLOSED)
                    || (geoLookupFuture.join().getReason() != FailureReason.NONE && ConnectionGuard.getSettings().geoFailure == GuardSettings.FailurePolicy.CLOSED))) {
                loginEvent.setCancelReason(new TextComponent("Connection verification is temporarily unavailable. Please retry shortly."));
                loginEvent.setCancelled(true);
                return;
            }
            if (vpnResult.isVpn() && !hasVpnExemption) {
                // Check if staff should be notified
                if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    String notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", loginEvent.getConnection().getName())
                    );
                    broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                }

                // Check if command should be executed on flag
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                            ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                            ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replace("%NAME%", loginEvent.getConnection().getName())
                                    .replace("%IP%", ipAddress));
                }

                // Check if WebHook should be executed
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-webhook")
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    String kickMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", loginEvent.getConnection().getName())
                    );

                    loginEvent.setCancelReason(new TextComponent(kickMessage));
                    loginEvent.setCancelled(true);

                    return;
                }
            }

            Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
            if (geoResultOptional.isPresent() && !hasGeoExemption) {
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
                    // Check if staff should be notified
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        String notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-notify")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (!ConnectionGuard.getSettings().observe && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                                        .replace("%IP%", ipAddress));
                    }

                    // Check if WebHook should be executed
                    if (!ConnectionGuard.getSettings().observe && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-webhook")
                                .replace("%NAME%", loginEvent.getConnection().getName())
                                .replace("%IP%", ipAddress)
                                .replace("%COUNTRY%", geoResult.getCountryName())
                                .replace("%CITY%", geoResult.getCityName())
                                .replace("%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (!ConnectionGuard.getSettings().observe && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
                        String kickMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-block")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                        );

                        loginEvent.setCancelReason(new TextComponent(kickMessage));
                        loginEvent.setCancelled(true);
                            return;
                    }
                }
            }

        }).whenComplete((ignored, error) -> loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance()));

    }

    private void broadcastMessage(String message, String permission) {
        for (ProxiedPlayer proxiedPlayer : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
            if (proxiedPlayer.hasPermission(permission)) {
                proxiedPlayer.sendMessage(TextComponent.fromLegacyText(message));
            }
        }
    }
}
