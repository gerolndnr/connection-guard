package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
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
import java.util.UUID;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.concurrent.CompletableFuture;

public class AsyncPlayerPreLoginListener implements Listener {
    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent preLoginEvent) {
        String rawIp = preLoginEvent.getAddress().getHostAddress();

        final String ipAddress = Exemptions.normalize(rawIp);
        final String clientIp = ipAddress;
        UUID uuid = preLoginEvent.getUniqueId();
        boolean trusted = Bukkit.getOnlineMode() || ConnectionGuard.getSettings().trustForwardedIdentity;
        Optional<AccessRule> vpnAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.VPN);
        Optional<AccessRule> geoAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.GEO);
        if (!ConnectionGuard.getSettings().observe && ((vpnAccess.isPresent() && vpnAccess.get().getEffect() == AccessRule.Effect.DENY)
                || (geoAccess.isPresent() && geoAccess.get().getEffect() == AccessRule.Effect.DENY))) {
            preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Connection denied by server access policy."); return;
        }
        CompletableFuture<Boolean> hasVpnExemptionPermissionFuture = (vpnAccess.isPresent() && vpnAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions"), clientIp, uuid, trusted)
                ? CompletableFuture.completedFuture(true) : trusted && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                    ? CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn") : CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> hasGeoExemptionPermissionFuture = (geoAccess.isPresent() && geoAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions"), clientIp, uuid, trusted)
                ? CompletableFuture.completedFuture(true) : trusted && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                    ? CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo") : CompletableFuture.completedFuture(false);
        CompletableFuture<VpnResult> vpnResultFuture = hasVpnExemptionPermissionFuture.thenCompose(exempt -> exempt
                ? CompletableFuture.completedFuture(new VpnResult(clientIp, false)) : ConnectionGuard.getVpnResult(clientIp));
        CompletableFuture<GeoLookup> geoLookupFuture = hasGeoExemptionPermissionFuture.thenCompose(exempt -> exempt
                ? CompletableFuture.completedFuture(new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0)) : ConnectionGuard.getGeoLookup(clientIp));
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture = geoLookupFuture.thenApply(GeoLookup::getResult);

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture, hasVpnExemptionPermissionFuture, hasGeoExemptionPermissionFuture).join();

        long asOf = System.currentTimeMillis();
            VpnResult vpnResult = LookupFreshness.vpn(vpnResultFuture.join(), asOf);
            GeoLookup currentGeo = LookupFreshness.geo(geoLookupFuture.join(), asOf);
        Boolean hasVpnExemptionPermission = hasVpnExemptionPermissionFuture.join();
        Boolean hasGeoExemptionPermission = hasGeoExemptionPermissionFuture.join();


            EvidencePolicy.Decision vpnPolicy = ConnectionGuard.evidenceRule(clientIp, uuid, trusted, AccessRule.Scope.VPN, vpnResult, currentGeo);
            EvidencePolicy.Decision geoPolicy = ConnectionGuard.evidenceRule(clientIp, uuid, trusted, AccessRule.Scope.GEO, vpnResult, currentGeo);
            boolean vpnBypassed = hasVpnExemptionPermission || vpnPolicy.isBypassed();
            boolean geoBypassed = hasGeoExemptionPermission || geoPolicy.isBypassed();
            if (!ConnectionGuard.getSettings().observe && ((!hasVpnExemptionPermission && vpnPolicy.isDenied()) || (!hasGeoExemptionPermission && geoPolicy.isDenied()))) {
                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Connection denied by server access policy.");
                return;
            }
            if (!ConnectionGuard.getSettings().observe && (
                    (!vpnBypassed && (vpnResult.getStatus() == ProviderVote.Status.UNKNOWN || vpnPolicy.isUnresolved()) && ConnectionGuard.getSettings().vpnFailure == GuardSettings.FailurePolicy.CLOSED)
                    || (!geoBypassed && (currentGeo.getReason() != FailureReason.NONE || geoPolicy.isUnresolved()) && ConnectionGuard.getSettings().geoFailure == GuardSettings.FailurePolicy.CLOSED))) {
                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Connection verification is temporarily unavailable. Please retry shortly.");
                return;
            }
        if (vpnResult.isVpn() && !vpnBypassed) {
            // Check if staff should be notified
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                String notifyMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                .replace("%IP%", vpnResult.getIpAddress())
                                .replace("%NAME%", preLoginEvent.getName())
                );
                int amountRecipients = ConnectionGuardSpigotPlugin.getInstance().getServer().broadcast(notifyMessage, "connectionguard.notify.vpn");
            }

            // Check if command should be executed on flag
            if (!ConnectionGuard.getSettings().observe && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
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
            if (!ConnectionGuard.getSettings().observe && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-webhook")
                        .replace("%NAME%", preLoginEvent.getName())
                        .replace("%IP%", ipAddress);
                String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
            }

            // Check if player should be kicked
            if (!ConnectionGuard.getSettings().observe && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
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

        Optional<GeoResult> geoResultOptional = currentGeo.getResult();
        if (geoResultOptional.isPresent() && !geoBypassed) {
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
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
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
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
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
                if (!ConnectionGuard.getSettings().observe && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
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
