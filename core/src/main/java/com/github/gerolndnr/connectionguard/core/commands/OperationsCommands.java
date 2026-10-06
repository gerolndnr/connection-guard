package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Shared secret-free operations on every platform. The adapter owns response thread dispatch. */
public final class OperationsCommands {
    public static final List<String> NAMES = Arrays.asList("doctor", "providers", "stats", "explain", "allow", "deny", "exempt", "local", "cloud", "policy");
    private OperationsCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        if (CloudCommands.handle(args, permission, reply)) return true;
        if (LocalDataCommands.handle(args, permission, reply)) return true;
        if (RulesCommands.handle(args, permission, reply)) return true;
        if (PolicyCommands.handle(args, permission, reply)) return true;
        if (args.length == 0 || !NAMES.contains(args[0].toLowerCase(Locale.ROOT))) return false;
        String operation = args[0].toLowerCase(Locale.ROOT);
        if (!permission.test("connectionguard.command." + operation)) { reply.accept(messages.getString("ops.permission")); return true; }
        if (operation.equals("explain")) {
            if (args.length != 2) { reply.accept(messages.getString("ops.explain-usage")); return true; }
            final String ip;
            try { ip = Exemptions.normalize(args[1]); }
            catch (IllegalArgumentException invalid) { reply.accept(messages.getString("ops.literal")); return true; }
            ConnectionGuard.getVpnResult(ip).thenCombine(ConnectionGuard.getGeoLookup(ip), (rawResult, rawGeo) -> {
                long asOf = System.currentTimeMillis();
                com.github.gerolndnr.connectionguard.core.vpn.VpnResult result = com.github.gerolndnr.connectionguard.core.lookup.LookupFreshness.vpn(rawResult, asOf);
                com.github.gerolndnr.connectionguard.core.lookup.GeoLookup geo = com.github.gerolndnr.connectionguard.core.lookup.LookupFreshness.geo(rawGeo, asOf);
                reply.accept("VPN=" + result.getStatus() + " threshold=" + ConnectionGuard.getRequiredPositiveFlags()
                        + " cached=" + result.isFromCache() + " ageMs=" + (result.getCachedOn() == 0 ? "unavailable" : Math.max(0, System.currentTimeMillis() - result.getCachedOn())));
                for (ProviderVote vote : result.getVotes()) reply.accept(vote.getProvider() + "=" + vote.getStatus() + " reason=" + vote.getReason()
                        + (vote.getProvider().equals("blackbox") && vote.getStatus() == ProviderVote.Status.POSITIVE
                            ? " evidence=" + com.github.gerolndnr.connectionguard.core.vpn.BlackboxVpnProvider.LISTED_REASON : "")
                        + (vote.getProvider().equals("connectionguard-intel") && vote.getDetails().getDataAsOf() != null
                            ? " evidence=Connection Guard Intel " + vote.getDetails().getClassifications() + " (" + java.time.Instant.ofEpochMilli(vote.getDetails().getDataAsOf()) + ")" : "")
                        + " durationMs=" + vote.getDurationMillis() + " version=" + vote.getSourceVersion() + " validUntil=" + vote.getValidUntil() + " " + vote.getDetails().describe());
                reply.accept("Geo=" + (geo.getResult().isPresent() ? geo.getResult().get().getCountryName() : "UNKNOWN")
                        + " reason=" + geo.getReason() + " cached=" + geo.isCached() + " durationMs=" + geo.getDurationMillis());
                for (AccessRule.Scope scope : new AccessRule.Scope[]{AccessRule.Scope.VPN, AccessRule.Scope.GEO}) {
                    EvidencePolicy.Decision decision = ConnectionGuard.evidenceRule(ip, null, false, scope, result, geo);
                    reply.accept(scope + " " + decision.describe() + "; " + messages.getString("ops.identity-omitted"));
                    decision.getTrace().stream().limit(20).forEach(entry -> reply.accept(entry.getRule().getId() + " " + entry.getRule().getType() + "=" + entry.getMatch()));
                }
                return null;
            }).exceptionally(error -> { reply.accept(messages.getString("ops.explain-unavailable")); return null; });
            return true;
        }
        if (args.length != 1) { reply.accept(messages.text("ops.usage", operation)); return true; }
        if (operation.equals("stats")) { reply.accept(ConnectionGuard.lookupStats()); reply.accept("login-checks active=" + com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.active()); reply.accept(ConnectionGuard.admissionStats()); reply.accept(com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.describe()); reply.accept(com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.describe()); reply.accept(com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.describe()); }
        if (operation.equals("providers")) {
            if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().extensionProviders.forEach(provider -> reply.accept(provider.describe()));
            if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().localSnapshots.forEach(snapshot -> reply.accept(snapshot.describe(System.currentTimeMillis())));
            if (ConnectionGuard.providerHealth().isEmpty()) reply.accept(messages.getString("ops.no-provider-attempts"));
            ConnectionGuard.providerHealth().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> reply.accept(entry.getKey() + ": " + entry.getValue().describe()));
        }
        if (operation.equals("doctor")) for (String line : doctor(messages)) reply.accept(line);
        return true;
    }
    public static List<String> doctor() { return doctor(ConnectionGuard.getMessages()); }
    private static List<String> doctor(com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages) {
        List<String> lines = new ArrayList<>();
        synchronized (ConnectionGuard.class) {
            com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore store = ConnectionGuard.getRuleStore();
            if (store != null) lines.add("Policy owner=" + (store.locallyOwned() ? "LOCAL_VERSION" : "CONFIG")
                    + " revision=" + store.revision() + " activeDecisions=" + ConnectionGuard.activePolicyDecisions()
                    + (store.locallyOwned() ? "; release the local revision before changing config/dashboard decision fields" : ""));
        }
        lines.add("Mode=" + (ConnectionGuard.getSettings().observe ? "OBSERVE" : "ENFORCE")
                + " vpnFailure=" + ConnectionGuard.getSettings().vpnFailure + " geoFailure=" + ConnectionGuard.getSettings().geoFailure);
        lines.add("Lookup deadlineMs=" + ConnectionGuard.getSettings().lookup.deadlineMillis + " httpTimeoutMs=" + ConnectionGuard.getSettings().lookup.httpTimeoutMillis);
        lines.add("Cache=" + (ConnectionGuard.getCacheProvider() == null ? "unavailable" : ConnectionGuard.getCacheProvider().getClass().getSimpleName())
                + "; " + messages.getString("ops.cache-health"));
        if (ConnectionGuard.getCacheProvider() instanceof com.github.gerolndnr.connectionguard.core.cache.ResilientRedisCacheProvider)
            lines.add(((com.github.gerolndnr.connectionguard.core.cache.ResilientRedisCacheProvider) ConnectionGuard.getCacheProvider()).describe());
        com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration draft = ConnectionGuard.getActiveDraft();
        if (draft != null) lines.add("VPN strategy=" + (draft.failover ? "FAILOVER" : "CONSENSUS")
                + " effectiveThreshold=" + draft.threshold + " maxExternalAttempts=" + draft.externalAttempts + " order=" + draft.keys);
        lines.add(ConnectionGuard.torStatus());
        if (draft != null && draft.intelSettings.enabled) lines.add(draft.intelSnapshot.describe(System.currentTimeMillis()));
        lines.add(ConnectionGuard.uncheckedVpnAdmissions().snapshot().describe());
        lines.add("Geo=" + (ConnectionGuard.isGeoDisabled() ? "disabled by configuration; not checked" : "enabled; missing answers follow geoFailure"));
        ConnectionGuard.providerHealth().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> lines.add(entry.getKey() + ": " + entry.getValue().describe()));
        lines.add("Identity declaredForwarding=" + ConnectionGuard.getSettings().trustForwardedIdentity
                + " nativeFloodgate=" + ConnectionGuard.getSettings().nativeFloodgateIdentity
                + "; " + messages.getString("ops.identity-authority"));
        ConnectionGuard.getSettings().warnings.forEach(line -> lines.add(messages.translate(line)));
        if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().localSnapshots.forEach(snapshot -> lines.add(snapshot.describe(System.currentTimeMillis())));
        if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().extensionProviders.forEach(provider -> lines.add(provider.describe()));
        lines.add(messages.getString("ops.forwarding"));
        lines.add(ConnectionGuard.lookupStats()); lines.add("login-checks active=" + com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.active());
        lines.add(ConnectionGuard.admissionStats());
        lines.add(com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.describe());
        lines.add(com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.describe());
        lines.addAll(com.github.gerolndnr.connectionguard.core.cloud.CloudSync.describeLines());
        return lines;
    }
}
