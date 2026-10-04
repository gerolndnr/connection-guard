package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Best-effort notifications are independent of admission and require explicit operator selection. */
public final class CGWebHookHelper {
    private static final Gson GSON = new Gson();
    private static volatile WebhookDispatcher dispatcher = new WebhookDispatcher();
    private static volatile WebhookSettings selectedSettings;
    private static final AtomicLong invalid = new AtomicLong(), lastWarning = new AtomicLong(Long.MIN_VALUE);
    private CGWebHookHelper() {}
    public static synchronized void configure(WebhookSettings settings) {
        selectedSettings = Objects.requireNonNull(settings);
        sender();
    }
    private static synchronized WebhookDispatcher sender() {
        // Re-enable only after the retired worker has physically stopped; never overlap owners.
        if (selectedSettings != null && dispatcher.isClosed() && dispatcher.isTerminated()) dispatcher = new WebhookDispatcher();
        return dispatcher;
    }
    public static String describe() { return dispatcher.describe() + " invalid=" + invalid.get(); }
    /** Compatibility entry point. Mention suppression/bounds apply; no delivery retry. */
    public static CompletableFuture<Void> sendWebHook(String url, String content) {
        try { return send(url, new CGWebHookRequest(content), 0, () -> true); }
        catch (RuntimeException | LinkageError failure) { recordInvalid(); return CompletableFuture.completedFuture(null); }
    }
    public static CompletableFuture<Void> sendLegacy(WebhookSettings settings, Scope scope, String content) {
        WebhookSettings.Destination destination = scope == Scope.GEO ? settings.geo : settings.vpn;
        if (!destination.isLegacyText()) return CompletableFuture.completedFuture(null);
        try { return send(destination.endpoint(), new CGWebHookRequest(content), destination.cooldownMillis, () -> settings == selectedSettings); }
        catch (RuntimeException | LinkageError failure) { recordInvalid(); return CompletableFuture.completedFuture(null); }
    }
    public static void sendDecision(DecisionObservation event, WebhookSettings settings, int threshold) {
        for (Notification notification : plan(event, settings)) {
            try {
                send(notification.destination.endpoint(), CGWebHookRequest.embedded(event, notification.scopes, notification.includeIp, threshold),
                        notification.cooldown, () -> settings == selectedSettings);
            } catch (RuntimeException | LinkageError failure) { recordInvalid(); }
        }
    }
    static List<Notification> plan(DecisionObservation event, WebhookSettings settings) {
        if (event.getMode() == Mode.OBSERVE || !settings.hasEmbeds()) return Collections.emptyList();
        boolean vpn = selected(event, Scope.VPN, settings.vpn), geo = selected(event, Scope.GEO, settings.geo);
        if (vpn && geo && settings.vpn.endpoint().equals(settings.geo.endpoint())) {
            return Collections.singletonList(new Notification(settings.vpn, EnumSet.of(Scope.VPN, Scope.GEO),
                    settings.vpn.includeIp && settings.geo.includeIp, Math.max(settings.vpn.cooldownMillis, settings.geo.cooldownMillis)));
        }
        List<Notification> result = new ArrayList<>();
        if (vpn) result.add(new Notification(settings.vpn, EnumSet.of(Scope.VPN), settings.vpn.includeIp, settings.vpn.cooldownMillis));
        if (geo) result.add(new Notification(settings.geo, EnumSet.of(Scope.GEO), settings.geo.includeIp, settings.geo.cooldownMillis));
        return Collections.unmodifiableList(result);
    }
    static final class Notification {
        final WebhookSettings.Destination destination;
        final Set<Scope> scopes;
        final boolean includeIp;
        final int cooldown;
        private Notification(WebhookSettings.Destination destination, Set<Scope> scopes, boolean includeIp, int cooldown) {
            this.destination = destination; this.scopes = Collections.unmodifiableSet(scopes); this.includeIp = includeIp; this.cooldown = cooldown;
        }
    }
    static boolean selected(DecisionObservation event, Scope scope, WebhookSettings.Destination destination) {
        if (!destination.enabled || destination.format != WebhookSettings.Format.EMBED || event.getMode() == Mode.OBSERVE) return false;
        boolean error = event.getOutcome() == Outcome.ERROR || event.hasProcessingError();
        boolean global = event.getReason() == Reason.EXTERNAL_POLICY || event.getReason() == Reason.EXTERNAL_UNAVAILABLE
                || event.getReason() == Reason.OVERLOAD || event.getReason() == Reason.IDENTITY_UNAVAILABLE || error;
        boolean flag = event.getFlags().contains(scope == Scope.VPN ? Flag.VPN : Flag.GEO);
        boolean access = event.getRules().stream().anyMatch(rule -> rule.isSelected() && rule.getEffect() == Effect.DENY
                && (rule.getEvaluatedScope() == scope || rule.getEvaluatedScope() == Scope.ALL));
        boolean unknown = (scope == Scope.VPN ? event.getVpnCheck() : event.getGeoCheck()) == Check.UNKNOWN;
        boolean relevant = global || flag || access || unknown;
        return relevant && (destination.events.contains(WebhookSettings.Event.FLAG) && (flag || access)
                || destination.events.contains(WebhookSettings.Event.DENY) && event.getOutcome() == Outcome.DENY
                || destination.events.contains(WebhookSettings.Event.ERROR) && error
                || destination.events.contains(WebhookSettings.Event.UNKNOWN) && (unknown || event.getReason() == Reason.EXTERNAL_UNAVAILABLE
                        || event.getReason() == Reason.IDENTITY_UNAVAILABLE));
    }
    private static CompletableFuture<Void> send(String endpoint, CGWebHookRequest request, int cooldown, BooleanSupplier current) {
        return sender().submit(endpoint, GSON.toJson(request), cooldown, current).thenAccept(result -> {
            if (result == WebhookDispatcher.Result.UNKNOWN || result == WebhookDispatcher.Result.HTTP_ERROR) warn();
        });
    }
    private static void warn() {
        java.util.logging.Logger logger = ConnectionGuard.getLogger();
        if (logger == null) return;
        long now = System.nanoTime(), previous = lastWarning.get();
        if (previous != Long.MIN_VALUE && now - previous < TimeUnit.SECONDS.toNanos(30)
                || !lastWarning.compareAndSet(previous, now)) return;
        logger.warning("Webhook unavailable; inspect notification settings and stats (details redacted; no retry).");
    }
    /** Internal diagnostic counter; never retains renderer data or changes the connection result. */
    public static void recordInvalid() { invalid.incrementAndGet(); }
    public static synchronized void shutdown() { selectedSettings = null; dispatcher.close(); }
}
