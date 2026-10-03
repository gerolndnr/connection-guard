package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.core.admission.LoginAdmission;
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
import java.util.UUID;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import java.util.function.Consumer;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityListener {
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed() || ConnectionGuard.getSettings().observe) return;
        String ip = Exemptions.normalize(event.getConnection().getRemoteAddress().getAddress().getHostAddress());
        // Literal network denies can run before authentication. Identity is never trusted here.
        Optional<AccessRule> vpn = ConnectionGuard.accessRule(ip, null, false, AccessRule.Scope.VPN);
        Optional<AccessRule> geo = ConnectionGuard.accessRule(ip, null, false, AccessRule.Scope.GEO);
        if ((vpn.isPresent() && vpn.get().getEffect() == AccessRule.Effect.DENY)
                || (geo.isPresent() && geo.get().getEffect() == AccessRule.Effect.DENY)) {
            try (DecisionCapture decision = DecisionCapture.begin(DecisionObservation.Platform.VELOCITY,
                    DecisionObservation.Phase.PRE_AUTHENTICATION, ip, null, DecisionObservation.IdentityTrust.UNTRUSTED)) {
                decision.manual(vpn, geo);
                if (!decision.observe()) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Connection denied by server access policy.")));
                    decision.denied(DecisionObservation.Reason.ACCESS_RULE);
                }
            }
        }
    }
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) return null;
        long startedNanos = System.nanoTime();
        Player player = event.getPlayer();
        // A single stable phase: rule expiry/reload between events cannot skip the provider check.
        com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity identity =
                com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity.resolve(player.getUniqueId(), player.getUsername(),
                        player.getRemoteAddress(), player::isActive, player.isOnlineMode(), false,
                        ConnectionGuard.getSettings().trustForwardedIdentity, ConnectionGuard.getSettings().nativeFloodgateIdentity);
        return EventTask.withContinuation(continuation -> checkConnection(player.getRemoteAddress().getAddress().getHostAddress(),
                player.getUniqueId(), player.getUsername(), identity, startedNanos,
                player, message -> event.setResult(ResultedEvent.ComponentResult.denied(message)))
                .whenComplete((ignored, error) -> continuation.resume()));
    }
    private CompletableFuture<Void> checkConnection(String rawIp, UUID uuid, String playerUsername, com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity identity, long startedNanos,
                                                     Object subject, Consumer<Component> deny) {
        final String ipAddress = Exemptions.normalize(rawIp);
        final boolean trusted = identity.isTrusted();
        DecisionCapture decision = DecisionCapture.begin(DecisionObservation.Platform.VELOCITY, DecisionObservation.Phase.LOGIN, ipAddress, uuid, identity.observationTrust(), startedNanos);
        try {
            Optional<AccessRule> vpnAccess = ConnectionGuard.accessRule(ipAddress, uuid, trusted, AccessRule.Scope.VPN);
            Optional<AccessRule> geoAccess = ConnectionGuard.accessRule(ipAddress, uuid, trusted, AccessRule.Scope.GEO);
            decision.manual(vpnAccess, geoAccess);
            if (!decision.observe() && ((vpnAccess.isPresent() && vpnAccess.get().getEffect() == AccessRule.Effect.DENY)
                    || (geoAccess.isPresent() && geoAccess.get().getEffect() == AccessRule.Effect.DENY))) {
                deny.accept(Component.text("Connection denied by server access policy.")); decision.denied(DecisionObservation.Reason.ACCESS_RULE); decision.close(); return CompletableFuture.completedFuture(null);
            }
            LoginChecks.Permission vpnPermission = (vpnAccess.isPresent() && vpnAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions"), ipAddress, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn", subject)) : LoginChecks.Permission.known(false);
            LoginChecks.Permission geoPermission = (geoAccess.isPresent() && geoAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions"), ipAddress, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo", subject)) : LoginChecks.Permission.known(false);
            return LoginChecks.check(ipAddress, decision.settings().lookup, decision.startedNanos(), decision.observe(),
                    vpnPermission, geoPermission).thenAccept(checks -> {
                if (identity.requiresCurrentProof() && !identity.isCurrent()) {
                    decision.identityUnavailable();
                    if (!decision.observe()) { deny.accept(Component.text("Connection identity verification is no longer available. Please retry.")); decision.denied(DecisionObservation.Reason.IDENTITY_UNAVAILABLE); }
                    return;
                }
                if (checks.isCancelled()) { decision.error(); return; }
                if (!checks.isAdmitted()) {
                    decision.overload();
                    if (checks.shouldDenyAdmission()) {
                        deny.accept(Component.text("Connection checks are temporarily busy. Please retry shortly."));
                        decision.denied(DecisionObservation.Reason.OVERLOAD);
                    }
                    return;
                }
                long asOf = System.currentTimeMillis();
                VpnResult vpnResult = LookupFreshness.vpn(checks.vpn(), asOf);
                GeoLookup currentGeo = LookupFreshness.geo(checks.geo(), asOf);
                Optional<GeoResult> geoResultOptional = currentGeo.getResult();
                decision.facts(vpnResult, currentGeo, checks.vpnExempt(), checks.geoExempt(), asOf);
                EvidencePolicy.Decision vpnPolicy = ConnectionGuard.evidenceRule(ipAddress, uuid, trusted, AccessRule.Scope.VPN, vpnResult, currentGeo);
                EvidencePolicy.Decision geoPolicy = ConnectionGuard.evidenceRule(ipAddress, uuid, trusted, AccessRule.Scope.GEO, vpnResult, currentGeo);
                decision.policy(vpnPolicy, geoPolicy);
                boolean vpnBypassed = checks.vpnExempt() || vpnPolicy.isBypassed();
                boolean geoBypassed = checks.geoExempt() || geoPolicy.isBypassed();
                if (!decision.observe() && ((!checks.vpnExempt() && vpnPolicy.isDenied()) || (!checks.geoExempt() && geoPolicy.isDenied()))) {
                    deny.accept(Component.text("Connection denied by server access policy.")); decision.denied(DecisionObservation.Reason.ACCESS_RULE);
                    return;
                }
                if (!decision.observe() && (
                        (!vpnBypassed && (vpnResult.getStatus() == ProviderVote.Status.UNKNOWN || vpnPolicy.isUnresolved()) && decision.settings().vpnFailure == GuardSettings.FailurePolicy.CLOSED)
                        || (!geoBypassed && (currentGeo.getReason() != FailureReason.NONE || geoPolicy.isUnresolved()) && decision.settings().geoFailure == GuardSettings.FailurePolicy.CLOSED))) {
                    deny.accept(Component.text("Connection verification is temporarily unavailable. Please retry shortly.")); decision.denied(DecisionObservation.Reason.LOOKUP_UNAVAILABLE);
                    return;
                }
                if (vpnResult.isVpn() && !vpnBypassed) {
                    decision.flag(DecisionObservation.Flag.VPN);
                    // Check if staff should be notified
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                        Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-notify")
                                        .replace("%IP%", vpnResult.getIpAddress())
                                        .replace("%NAME%", playerUsername)
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                    }

                    // Check if command should be executed on flag
                    if (!decision.observe() && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                        ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.execute-command.command")
                                        .replace("%NAME%", playerUsername)
                                        .replace("%IP%", ipAddress)
                        );
                    }

                    // Check if WebHook should be executed
                    if (!decision.observe() && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-webhook")
                                .replace("%NAME%", playerUsername)
                                .replace("%IP%", ipAddress);
                        String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (!decision.observe() && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.kick-player")) {
                        Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-block")
                                        .replace("%IP%", vpnResult.getIpAddress())
                                        .replace("%NAME%", playerUsername)
                        );

                        deny.accept(kickMessage); decision.denied(DecisionObservation.Reason.VPN_FLAG);
                        return;
                    }
                }

                if (geoResultOptional.isPresent() && !geoBypassed) {
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
                        decision.flag(DecisionObservation.Flag.GEO);
                        // Check if staff should be notified
                        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.notify-staff")) {
                            Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-notify")
                                            .replace("%IP%", geoResult.getIpAddress())
                                            .replace("%COUNTRY%", geoResult.getCountryName())
                                            .replace("%CITY%", geoResult.getCityName())
                                            .replace("%ISP%", geoResult.getIspName())
                                            .replace("%NAME%", playerUsername)
                            );
                            broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                        }

                        // Check if command should be executed on flag
                        if (!decision.observe() && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.execute-command.command")
                                            .replace("%NAME%", playerUsername)
                                            .replace("%IP%", ipAddress)
                                            .replace("%COUNTRY%", geoResult.getCountryName())
                            );
                        }

                        // Check if WebHook should be executed
                        if (!decision.observe() && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                            String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-webhook")
                                    .replace("%NAME%", playerUsername)
                                    .replace("%IP%", ipAddress)
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName());
                            String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.send-webhook.url");

                            CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                        }

                        // Check if player should be kicked
                        if (!decision.observe() && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.kick-player")) {
                            Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-block")
                                            .replace("%IP%", geoResult.getIpAddress())
                                            .replace("%COUNTRY%", geoResult.getCountryName())
                                            .replace("%CITY%", geoResult.getCityName())
                                            .replace("%ISP%", geoResult.getIspName())
                                            .replace("%NAME%", playerUsername)
                            );

                            deny.accept(kickMessage); decision.denied(DecisionObservation.Reason.GEO_FLAG);
                            return;
                        }
                    }

                }
            }).whenComplete((ignored, error) -> { if (error != null) decision.error(); decision.close(); });
        } catch (RuntimeException | LinkageError failure) {
            decision.error(); decision.close(); throw failure;
        }
    }

    private void broadcastMessage(Component message, String permission) {
        for (Player player : ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getAllPlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }
}
