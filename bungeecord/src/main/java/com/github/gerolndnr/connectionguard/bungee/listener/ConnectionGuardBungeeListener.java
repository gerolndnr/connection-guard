package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
import com.github.gerolndnr.connectionguard.bungee.ConnectionGuardBungeePlugin;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.core.admission.LoginAdmission;
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
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    @EventHandler
    public void onLogin(LoginEvent loginEvent) {
        if (loginEvent.isCancelled()) return;
        loginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String rawIp = loginEvent.getConnection().getAddress().getAddress().getHostAddress();

        final String ipAddress = Exemptions.normalize(rawIp);
        final String clientIp = ipAddress;
        UUID uuid = loginEvent.getConnection().getUniqueId();
        boolean trusted = loginEvent.getConnection().isOnlineMode() || ConnectionGuard.getSettings().trustForwardedIdentity;
        DecisionCapture decision = DecisionCapture.begin(DecisionObservation.Platform.BUNGEE, DecisionObservation.Phase.LOGIN, clientIp, uuid,
                loginEvent.getConnection().isOnlineMode() ? DecisionObservation.IdentityTrust.AUTHENTICATED : trusted ? DecisionObservation.IdentityTrust.FORWARDED : DecisionObservation.IdentityTrust.UNTRUSTED);
        try {
            Optional<AccessRule> vpnAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.VPN);
            Optional<AccessRule> geoAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.GEO);
            decision.manual(vpnAccess, geoAccess);
            if (!decision.observe() && ((vpnAccess.isPresent() && vpnAccess.get().getEffect() == AccessRule.Effect.DENY)
                    || (geoAccess.isPresent() && geoAccess.get().getEffect() == AccessRule.Effect.DENY))) {
                loginEvent.setCancelReason(new TextComponent("Connection denied by server access policy.")); loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.ACCESS_RULE);
                decision.close();
                loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance()); return;
            }
            LoginChecks.Permission vpnPermission = (vpnAccess.isPresent() && vpnAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions"), clientIp, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn")) : LoginChecks.Permission.known(false);
            LoginChecks.Permission geoPermission = (geoAccess.isPresent() && geoAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions"), clientIp, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo")) : LoginChecks.Permission.known(false);
            LoginChecks.check(clientIp, decision.settings().lookup, decision.startedNanos(), decision.observe(),
                    vpnPermission, geoPermission).thenAccept(checks -> {
                if (checks.isCancelled()) { decision.error(); return; }
                if (!checks.isAdmitted()) {
                    decision.overload();
                    if (checks.shouldDenyAdmission()) {
                        loginEvent.setCancelReason(new TextComponent("Connection checks are temporarily busy. Please retry shortly."));
                        loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.OVERLOAD);
                    }
                    return;
                }
                long asOf = System.currentTimeMillis();
                VpnResult vpnResult = LookupFreshness.vpn(checks.vpn(), asOf);
                GeoLookup currentGeo = LookupFreshness.geo(checks.geo(), asOf);
                Boolean hasVpnExemption = checks.vpnExempt();
                Boolean hasGeoExemption = checks.geoExempt();
                decision.facts(vpnResult, currentGeo, hasVpnExemption, hasGeoExemption, asOf);


                EvidencePolicy.Decision vpnPolicy = ConnectionGuard.evidenceRule(clientIp, uuid, trusted, AccessRule.Scope.VPN, vpnResult, currentGeo);
                EvidencePolicy.Decision geoPolicy = ConnectionGuard.evidenceRule(clientIp, uuid, trusted, AccessRule.Scope.GEO, vpnResult, currentGeo);
                decision.policy(vpnPolicy, geoPolicy);
                boolean vpnBypassed = hasVpnExemption || vpnPolicy.isBypassed();
                boolean geoBypassed = hasGeoExemption || geoPolicy.isBypassed();
                if (!decision.observe() && ((!hasVpnExemption && vpnPolicy.isDenied()) || (!hasGeoExemption && geoPolicy.isDenied()))) {
                    loginEvent.setCancelReason(new TextComponent("Connection denied by server access policy.")); loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.ACCESS_RULE);
                    return;
                }
                if (!decision.observe() && (
                        (!vpnBypassed && (vpnResult.getStatus() == ProviderVote.Status.UNKNOWN || vpnPolicy.isUnresolved()) && decision.settings().vpnFailure == GuardSettings.FailurePolicy.CLOSED)
                        || (!geoBypassed && (currentGeo.getReason() != FailureReason.NONE || geoPolicy.isUnresolved()) && decision.settings().geoFailure == GuardSettings.FailurePolicy.CLOSED))) {
                    loginEvent.setCancelReason(new TextComponent("Connection verification is temporarily unavailable. Please retry shortly."));
                    loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.LOOKUP_UNAVAILABLE);
                    return;
                }
                if (vpnResult.isVpn() && !vpnBypassed) {
                    decision.flag(DecisionObservation.Flag.VPN);
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
                    if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                        ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                                        .replace("%IP%", ipAddress));
                    }

                    // Check if WebHook should be executed
                    if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-webhook")
                                .replace("%NAME%", loginEvent.getConnection().getName())
                                .replace("%IP%", ipAddress);
                        String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                        String kickMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                                        .replace("%IP%", vpnResult.getIpAddress())
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                        );

                        loginEvent.setCancelReason(new TextComponent(kickMessage));
                        loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.VPN_FLAG);

                        return;
                    }
                }

                Optional<GeoResult> geoResultOptional = currentGeo.getResult();
                if (geoResultOptional.isPresent() && !geoBypassed) {
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
                        decision.flag(DecisionObservation.Flag.GEO);
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
                        if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                            ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                    ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                    ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                            .replace("%NAME%", loginEvent.getConnection().getName())
                                            .replace("%IP%", ipAddress));
                        }

                        // Check if WebHook should be executed
                        if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
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
                        if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
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
                            loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.GEO_FLAG);
                                return;
                        }
                    }
                }

            }).whenComplete((ignored, error) -> {
                try { if (error != null) decision.error(); decision.close(); }
                finally { loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance()); }
            });
        } catch (RuntimeException | LinkageError failure) {
            decision.error(); decision.close();
            loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            throw failure;
        }

    }

    private void broadcastMessage(String message, String permission) {
        for (ProxiedPlayer proxiedPlayer : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
            if (proxiedPlayer.hasPermission(permission)) {
                proxiedPlayer.sendMessage(TextComponent.fromLegacyText(message));
            }
        }
    }
}
