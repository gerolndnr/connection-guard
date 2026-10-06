package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy;
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
import com.github.gerolndnr.connectionguard.core.identity.AuthenticatedIdentity;
import com.github.gerolndnr.connectionguard.core.lookup.*;
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
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(decision.messages().getString("messages.access-denied"))));
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
        java.util.UUID identityUuid = player.getUniqueId();
        String identityName = player.getUsername();
        java.net.InetSocketAddress identityAddress = player.getRemoteAddress();
        AuthenticatedIdentity.Probe authenticated = AuthenticatedIdentity.capture(identityUuid, identityName, identityAddress,
                () -> new AuthenticatedIdentity.State(player.getUniqueId(), player.getUsername(), player.getRemoteAddress(),
                        player.isOnlineMode(), player.isActive()));
        // A single stable phase: rule expiry/reload between events cannot skip the provider check.
        com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity identity =
                com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity.resolve(identityUuid, identityName,
                        identityAddress, player::isActive, authenticated, false,
                        ConnectionGuard.getSettings().trustForwardedIdentity, ConnectionGuard.getSettings().nativeFloodgateIdentity);
        return EventTask.withContinuation(continuation -> checkConnection(identityAddress.getAddress().getHostAddress(),
                identityUuid, identityName, identity, startedNanos,
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
                deny.accept(Component.text(decision.messages().getString("messages.access-denied"))); decision.denied(DecisionObservation.Reason.ACCESS_RULE); decision.close(); return CompletableFuture.completedFuture(null);
            }
            LoginChecks.Permission vpnPermission = (vpnAccess.isPresent() && vpnAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions"), ipAddress, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.vpn", subject)) : LoginChecks.Permission.known(false);
            LoginChecks.Permission geoPermission = (geoAccess.isPresent() && geoAccess.get().getEffect() != AccessRule.Effect.DENY) || Exemptions.matches(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions"), ipAddress, uuid, trusted)
                    ? LoginChecks.Permission.known(true) : trusted && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")
                        ? LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.hasPermission(uuid, "connectionguard.exemption.geo", subject)) : LoginChecks.Permission.known(false);
            return LoginChecks.check(ipAddress, decision.settings().lookup, decision.startedNanos(), decision.observe(),
                    vpnPermission, geoPermission, new com.github.gerolndnr.connectionguard.api.v1.AdmissionRequest(ipAddress, identity.isVerified() ? uuid : null, identity.observationTrust(), DecisionObservation.Platform.VELOCITY, decision.startedNanos() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(decision.settings().lookup.deadlineMillis))).thenAccept(checks -> {
                if (identity.requiresCurrentProof() && !identity.isCurrent()) {
                    decision.identityUnavailable();
                    if (!decision.observe()) { deny.accept(Component.text(decision.messages().getString("messages.identity-unavailable"))); decision.denied(DecisionObservation.Reason.IDENTITY_UNAVAILABLE); }
                    return;
                }

                LoginChecks.External external = checks.external();
                decision.admission(external.observations);
                if (external.isDenied()) decision.flag(DecisionObservation.Flag.EXTERNAL_POLICY);
                if (external.shouldRefuse(decision.observe())) {
                    String message = external.isDenied() ? decision.messages().getString("messages.external-denied") : decision.messages().getString("messages.external-unavailable");
                    deny.accept(Component.text(message));
                    decision.denied(external.refusalReason()); return;
                }
                if (checks.isCancelled()) { decision.error(); return; }
            if (!checks.isAdmitted()) {
                    decision.overload(checks.vpnExempt());
                    if (checks.shouldDenyAdmission()) {
                        deny.accept(Component.text(decision.messages().getString("messages.busy")));
                        decision.denied(DecisionObservation.Reason.OVERLOAD);
                    }
                    return;
                }
                long asOf = System.currentTimeMillis();
                ConnectionPolicy.Evaluation policy = ConnectionGuard.evaluatePolicy(decision.settings(), ipAddress, uuid, trusted,
                        checks.vpnExempt(), checks.geoExempt(), checks.vpn(), checks.geo(), decision.policyGeoSource(), asOf);
                VpnResult vpnResult = policy.vpn;
                GeoLookup currentGeo = policy.geo;
                decision.facts(vpnResult, currentGeo, checks.vpnExempt(), checks.geoExempt(), asOf);
                decision.policy(policy.vpnRule, policy.geoRule);
                if (policy.earlyDenial != null) {
                    String key = policy.earlyDenial == DecisionObservation.Reason.ACCESS_RULE ? "messages.access-denied" : "messages.lookup-unavailable";
                    deny.accept(Component.text(decision.messages().getString(key)));
                    decision.denied(policy.earlyDenial); return;
                }
                if (policy.vpnFlag) {
                    decision.flag(DecisionObservation.Flag.VPN);
                    // Check if staff should be notified
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                        Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                decision.messages().getString("messages.vpn-notify")
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
                    if (!decision.observe() && decision.settings().webhooks.vpn.isLegacyText()) {
                        String webhookMessage = decision.messages().getString("messages.vpn-webhook")
                                .replace("%NAME%", playerUsername)
                                .replace("%IP%", ipAddress);
                        CGWebHookHelper.sendLegacy(decision.settings().webhooks, DecisionObservation.Scope.VPN, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (policy.denial == DecisionObservation.Reason.VPN_FLAG) {
                        Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                decision.messages().getString("messages.vpn-block") + "\n" + decision.messages().getString("messages.vpn-allow-hint")
                                        .replace("%IP%", vpnResult.getIpAddress())
                                        .replace("%NAME%", playerUsername)
                        );

                        deny.accept(kickMessage); decision.denied(DecisionObservation.Reason.VPN_FLAG);
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
                        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.notify-staff")) {
                            Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                    decision.messages().getString("messages.geo-notify")
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
                        if (!decision.observe() && decision.settings().webhooks.geo.isLegacyText()) {
                            String webhookMessage = decision.messages().getString("messages.geo-webhook")
                                    .replace("%NAME%", playerUsername)
                                    .replace("%IP%", ipAddress)
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName());
                            CGWebHookHelper.sendLegacy(decision.settings().webhooks, DecisionObservation.Scope.GEO, webhookMessage);
                        }

                        // Check if player should be kicked
                        if (policy.denial == DecisionObservation.Reason.GEO_FLAG) {
                            Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                    decision.messages().getString("messages.geo-block")
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
