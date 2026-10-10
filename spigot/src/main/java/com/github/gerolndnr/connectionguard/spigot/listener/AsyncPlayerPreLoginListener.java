package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.core.admission.LoginAdmission;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
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
import com.github.gerolndnr.connectionguard.core.identity.AuthenticatedIdentity;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.concurrent.CompletableFuture;

public class AsyncPlayerPreLoginListener implements Listener {
    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent preLoginEvent) {
        if (preLoginEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        long startedNanos = System.nanoTime();
        String rawIp = preLoginEvent.getAddress().getHostAddress();

        final String ipAddress = Exemptions.normalize(rawIp);
        final String clientIp = ipAddress;
        UUID uuid = preLoginEvent.getUniqueId();
        com.github.gerolndnr.connectionguard.spigot.PaperLoginConnection connection =
                com.github.gerolndnr.connectionguard.spigot.PaperLoginConnection.read(preLoginEvent);
        com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity identity =
                com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity.resolve(uuid, preLoginEvent.getName(), connection.address,
                        connection.connected, AuthenticatedIdentity.unavailable(), Bukkit.getOnlineMode(), ConnectionGuard.getSettings().trustForwardedIdentity,
                        ConnectionGuard.getSettings().nativeFloodgateIdentity, connection.forwardedProof(uuid, preLoginEvent.getName()));
        boolean trusted = identity.isTrusted();
        DecisionCapture decision = DecisionCapture.begin(DecisionObservation.Platform.BUKKIT, DecisionObservation.Phase.LOGIN, clientIp, uuid, identity.observationTrust(), startedNanos);
        decision.playerName(preLoginEvent.getName());
        try {
            Optional<AccessRule> vpnAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.VPN);
            Optional<AccessRule> geoAccess = ConnectionGuard.accessRule(clientIp, uuid, trusted, AccessRule.Scope.GEO);
            decision.manual(vpnAccess, geoAccess);
            if (!decision.observe() && ((vpnAccess.isPresent() && vpnAccess.get().getEffect() == AccessRule.Effect.DENY)
                    || (geoAccess.isPresent() && geoAccess.get().getEffect() == AccessRule.Effect.DENY))) {
                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, decision.messages().getString("messages.access-denied")); decision.denied(DecisionObservation.Reason.ACCESS_RULE); return;
            }
            LoginChecks.Permission vpnPermission = (vpnAccess.isPresent() && vpnAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions"), clientIp, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn")) : LoginChecks.Permission.known(false);
            LoginChecks.Permission geoPermission = (geoAccess.isPresent() && geoAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions"), clientIp, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo")) : LoginChecks.Permission.known(false);
            LoginChecks.Result checks = LoginChecks.check(clientIp, decision.settings().lookup, decision.startedNanos(), decision.observe(),
                    vpnPermission, geoPermission, new com.github.gerolndnr.connectionguard.api.v1.AdmissionRequest(clientIp, identity.isVerified() ? uuid : null, identity.observationTrust(), DecisionObservation.Platform.BUKKIT, decision.startedNanos() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(decision.settings().lookup.deadlineMillis))).join();
            if (identity.requiresCurrentProof() && !identity.isCurrent()) {
                decision.identityUnavailable();
                if (!decision.observe()) { preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, decision.messages().getString("messages.identity-unavailable")); decision.denied(DecisionObservation.Reason.IDENTITY_UNAVAILABLE); }
                return;
            }

                LoginChecks.External external = checks.external();
                decision.admission(external.observations);
                if (external.isDenied()) decision.flag(DecisionObservation.Flag.EXTERNAL_POLICY);
                if (external.shouldRefuse(decision.observe())) {
                    String message = external.isDenied() ? decision.messages().getString("messages.external-denied") : decision.messages().getString("messages.external-unavailable");
                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
                    decision.denied(external.refusalReason()); return;
                }
            if (checks.isCancelled()) { decision.error(); return; }
            if (!checks.isAdmitted()) {
                decision.overload(checks.vpnExempt());
                if (checks.shouldDenyAdmission()) {
                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, decision.messages().getString("messages.busy"));
                    decision.denied(DecisionObservation.Reason.OVERLOAD);
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
                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, decision.messages().getString(key));
                decision.denied(policy.earlyDenial); return;
            }
            if (policy.vpnFlag) {
                    decision.flag(DecisionObservation.Flag.VPN);
                // Check if staff should be notified
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    String notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            decision.messages().getString("messages.vpn-notify")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", preLoginEvent.getName())
                    );
                    ConnectionGuardSpigotPlugin.getInstance().tasks().broadcast(notifyMessage, "connectionguard.notify.vpn");
                }

                // Check if command should be executed on flag
                if (!decision.observe() && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardSpigotPlugin.getInstance().tasks().consoleCommand(ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command").replace("%NAME%", preLoginEvent.getName()).replace("%IP%", ipAddress));
                }


                // Check if player should be kicked
                if (policy.denial == DecisionObservation.Reason.VPN_FLAG) {
                    String kickMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            decision.messages().getString("messages.vpn-block") + "\n" + decision.messages().getString("messages.vpn-allow-hint")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", preLoginEvent.getName())
                    );

                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage); decision.denied(DecisionObservation.Reason.VPN_FLAG);
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
                    if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        String notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                decision.messages().getString("messages.geo-notify")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", preLoginEvent.getName())
                        );
                        ConnectionGuardSpigotPlugin.getInstance().tasks().broadcast(notifyMessage, "connectionguard.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (!decision.observe() && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardSpigotPlugin.getInstance().tasks().consoleCommand(ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command").replace("%NAME%", preLoginEvent.getName()).replace("%IP%", ipAddress).replace("%COUNTRY%", geoResult.getCountryName()));
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
                                        .replace("%NAME%", preLoginEvent.getName())
                        );

                        preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage); decision.denied(DecisionObservation.Reason.GEO_FLAG);
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.LOOKUP);
            decision.error(); throw failure;
        } finally { decision.close(); }
    }
}
