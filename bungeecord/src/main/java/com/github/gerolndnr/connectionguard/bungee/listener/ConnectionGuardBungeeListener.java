package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy;
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
import com.github.gerolndnr.connectionguard.core.identity.AuthenticatedIdentity;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    @EventHandler
    public void onLogin(LoginEvent loginEvent) {
        if (loginEvent.isCancelled()) return;
        long startedNanos = System.nanoTime();
        loginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String rawIp = loginEvent.getConnection().getAddress().getAddress().getHostAddress();

        final String ipAddress = Exemptions.normalize(rawIp);
        final String clientIp = ipAddress;
        UUID uuid = loginEvent.getConnection().getUniqueId();
        net.md_5.bungee.api.connection.PendingConnection identityConnection = loginEvent.getConnection();
        String identityName = identityConnection.getName();
        java.net.InetSocketAddress identityAddress = identityConnection.getAddress();
        AuthenticatedIdentity.Probe authenticated = AuthenticatedIdentity.capture(uuid, identityName, identityAddress,
                () -> new AuthenticatedIdentity.State(identityConnection.getUniqueId(), identityConnection.getName(), identityConnection.getAddress(),
                        identityConnection.isOnlineMode(), identityConnection.isConnected()));
        com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity identity =
                com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity.resolve(uuid, identityName,
                        identityAddress, loginEvent.getConnection()::isConnected,
                        authenticated, false, ConnectionGuard.getSettings().trustForwardedIdentity,
                        ConnectionGuard.getSettings().nativeFloodgateIdentity);
        boolean trusted = identity.isTrusted();
        DecisionCapture decision = DecisionCapture.begin(DecisionObservation.Platform.BUNGEE, DecisionObservation.Phase.LOGIN, clientIp, uuid, identity.observationTrust(), startedNanos);
        try {
            Optional<AccessRule> vpnAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.VPN);
            Optional<AccessRule> geoAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.GEO);
            decision.manual(vpnAccess, geoAccess);
            if (!decision.observe() && ((vpnAccess.isPresent() && vpnAccess.get().getEffect() == AccessRule.Effect.DENY)
                    || (geoAccess.isPresent() && geoAccess.get().getEffect() == AccessRule.Effect.DENY))) {
                loginEvent.setCancelReason(new TextComponent(decision.messages().getString("messages.access-denied"))); loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.ACCESS_RULE);
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
                    vpnPermission, geoPermission, new com.github.gerolndnr.connectionguard.api.v1.AdmissionRequest(clientIp, identity.isVerified() ? uuid : null, identity.observationTrust(), DecisionObservation.Platform.BUNGEE, decision.startedNanos() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(decision.settings().lookup.deadlineMillis))).thenAccept(checks -> {
                if (identity.requiresCurrentProof() && !identity.isCurrent()) {
                    decision.identityUnavailable();
                    if (!decision.observe()) { loginEvent.setCancelReason(new TextComponent(decision.messages().getString("messages.identity-unavailable"))); loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.IDENTITY_UNAVAILABLE); }
                    return;
                }

                LoginChecks.External external = checks.external();
                decision.admission(external.observations);
                if (external.isDenied()) decision.flag(DecisionObservation.Flag.EXTERNAL_POLICY);
                if (external.shouldRefuse(decision.observe())) {
                    String message = external.isDenied() ? decision.messages().getString("messages.external-denied") : decision.messages().getString("messages.external-unavailable");
                    loginEvent.setCancelReason(new TextComponent(message)); loginEvent.setCancelled(true);
                    decision.denied(external.refusalReason()); return;
                }
                if (checks.isCancelled()) { decision.error(); return; }
            if (!checks.isAdmitted()) {
                    decision.overload();
                    if (checks.shouldDenyAdmission()) {
                        loginEvent.setCancelReason(new TextComponent(decision.messages().getString("messages.busy")));
                        loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.OVERLOAD);
                    }
                    return;
                }
                long asOf = System.currentTimeMillis();
                ConnectionPolicy.Evaluation policy = ConnectionGuard.evaluatePolicy(decision.settings(), clientIp, uuid, trusted,
                        checks.vpnExempt(), checks.geoExempt(), checks.vpn(), checks.geo(), decision.policyGeoSource(), asOf);
                VpnResult vpnResult = policy.vpn;
                GeoLookup currentGeo = policy.geo;
                decision.facts(vpnResult, currentGeo, checks.vpnExempt(), checks.geoExempt(), asOf);
                decision.policy(policy.vpnRule, policy.geoRule);
                if (policy.earlyDenial != null) {
                    String key = policy.earlyDenial == DecisionObservation.Reason.ACCESS_RULE ? "messages.access-denied" : "messages.lookup-unavailable";
                    loginEvent.setCancelReason(new TextComponent(decision.messages().getString(key))); loginEvent.setCancelled(true);
                    decision.denied(policy.earlyDenial); return;
                }
                if (policy.vpnFlag) {
                    decision.flag(DecisionObservation.Flag.VPN);
                    // Check if staff should be notified
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                        String notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                decision.messages().getString("messages.vpn-notify")
                                        .replace("%IP%", vpnResult.getIpAddress())
                                        .replace("%NAME%", identityName)
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                    }

                    // Check if command should be executed on flag
                    if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                        ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                        .replace("%NAME%", identityName)
                                        .replace("%IP%", ipAddress));
                    }

                    // Check if WebHook should be executed
                    if (!decision.observe() && decision.settings().webhooks.vpn.isLegacyText()) {
                        String webhookMessage = decision.messages().getString("messages.vpn-webhook")
                                .replace("%NAME%", identityName)
                                .replace("%IP%", ipAddress);
                        CGWebHookHelper.sendLegacy(decision.settings().webhooks, DecisionObservation.Scope.VPN, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (policy.denial == DecisionObservation.Reason.VPN_FLAG) {
                        String kickMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                decision.messages().getString("messages.vpn-block") + "\n" + decision.messages().getString("messages.vpn-allow-hint")
                                        .replace("%IP%", vpnResult.getIpAddress())
                                        .replace("%NAME%", identityName)
                        );

                        loginEvent.setCancelReason(new TextComponent(kickMessage));
                        loginEvent.setCancelled(true); decision.denied(DecisionObservation.Reason.VPN_FLAG);

                        return;
                    }
                }

                Optional<GeoResult> geoResultOptional = currentGeo.getResult();
                if (policy.geoFlag) {
                    GeoResult geoResult = geoResultOptional.get();
                    boolean isGeoFlagged = policy.geoFlag;

                    if (isGeoFlagged) {
                        decision.flag(DecisionObservation.Flag.GEO);
                        // Check if staff should be notified
                        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                            String notifyMessage = ChatColor.translateAlternateColorCodes(
                                    '&',
                                    decision.messages().getString("messages.geo-notify")
                                            .replace("%IP%", geoResult.getIpAddress())
                                            .replace("%COUNTRY%", geoResult.getCountryName())
                                            .replace("%CITY%", geoResult.getCityName())
                                            .replace("%ISP%", geoResult.getIspName())
                                            .replace("%NAME%", identityName)
                            );
                            broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                        }

                        // Check if command should be executed on flag
                        if (!decision.observe() && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                            ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                    ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                    ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                            .replace("%NAME%", identityName)
                                            .replace("%IP%", ipAddress));
                        }

                        // Check if WebHook should be executed
                        if (!decision.observe() && decision.settings().webhooks.geo.isLegacyText()) {
                            String webhookMessage = decision.messages().getString("messages.geo-webhook")
                                    .replace("%NAME%", identityName)
                                    .replace("%IP%", ipAddress)
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName());
                            CGWebHookHelper.sendLegacy(decision.settings().webhooks, DecisionObservation.Scope.GEO, webhookMessage);
                        }

                        // Check if player should be kicked
                        if (policy.denial == DecisionObservation.Reason.GEO_FLAG) {
                            String kickMessage = ChatColor.translateAlternateColorCodes(
                                    '&',
                                    decision.messages().getString("messages.geo-block")
                                            .replace("%IP%", geoResult.getIpAddress())
                                            .replace("%COUNTRY%", geoResult.getCountryName())
                                            .replace("%CITY%", geoResult.getCityName())
                                            .replace("%ISP%", geoResult.getIspName())
                                            .replace("%NAME%", identityName)
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
