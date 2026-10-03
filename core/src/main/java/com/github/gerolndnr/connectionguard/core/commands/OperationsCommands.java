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
    public static final List<String> NAMES = Arrays.asList("doctor", "providers", "stats", "explain", "allow", "deny", "exempt", "local");
    private OperationsCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if (LocalDataCommands.handle(args, permission, reply)) return true;
        if (RulesCommands.handle(args, permission, reply)) return true;
        if (args.length == 0 || !NAMES.contains(args[0].toLowerCase(Locale.ROOT))) return false;
        String operation = args[0].toLowerCase(Locale.ROOT);
        if (!permission.test("connectionguard.command." + operation)) { reply.accept("You do not have permission for this command."); return true; }
        if (operation.equals("explain")) {
            if (args.length != 2) { reply.accept("Usage: /cg explain <literal IPv4/IPv6>"); return true; }
            final String ip;
            try { ip = Exemptions.normalize(args[1]); }
            catch (IllegalArgumentException invalid) { reply.accept("Use a literal IPv4/IPv6 address."); return true; }
            ConnectionGuard.getVpnResult(ip).thenCombine(ConnectionGuard.getGeoLookup(ip), (rawResult, rawGeo) -> {
                long asOf = System.currentTimeMillis();
                com.github.gerolndnr.connectionguard.core.vpn.VpnResult result = com.github.gerolndnr.connectionguard.core.lookup.LookupFreshness.vpn(rawResult, asOf);
                com.github.gerolndnr.connectionguard.core.lookup.GeoLookup geo = com.github.gerolndnr.connectionguard.core.lookup.LookupFreshness.geo(rawGeo, asOf);
                reply.accept("VPN=" + result.getStatus() + " threshold=" + ConnectionGuard.getRequiredPositiveFlags()
                        + " cached=" + result.isFromCache() + " ageMs=" + (result.getCachedOn() == 0 ? "unavailable" : Math.max(0, System.currentTimeMillis() - result.getCachedOn())));
                for (ProviderVote vote : result.getVotes()) reply.accept(vote.getProvider() + "=" + vote.getStatus() + " reason=" + vote.getReason() + " durationMs=" + vote.getDurationMillis() + " version=" + vote.getSourceVersion() + " validUntil=" + vote.getValidUntil() + " " + vote.getDetails().describe());
                reply.accept("Geo=" + (geo.getResult().isPresent() ? geo.getResult().get().getCountryName() : "UNKNOWN")
                        + " reason=" + geo.getReason() + " cached=" + geo.isCached() + " durationMs=" + geo.getDurationMillis());
                for (AccessRule.Scope scope : new AccessRule.Scope[]{AccessRule.Scope.VPN, AccessRule.Scope.GEO}) {
                    EvidencePolicy.Decision decision = ConnectionGuard.evidenceRule(ip, null, false, scope, result, geo);
                    reply.accept(scope + " " + decision.describe() + "; identity/permission exemptions not evaluated for a literal IP.");
                    decision.getTrace().stream().limit(20).forEach(entry -> reply.accept(entry.getRule().getId() + " " + entry.getRule().getType() + "=" + entry.getMatch()));
                }
                return null;
            }).exceptionally(error -> { reply.accept("Explanation unavailable (details redacted)."); return null; });
            return true;
        }
        if (args.length != 1) { reply.accept("Usage: /cg " + operation); return true; }
        if (operation.equals("stats")) { reply.accept(ConnectionGuard.lookupStats()); reply.accept("login-checks active=" + com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.active()); reply.accept(ConnectionGuard.admissionStats()); reply.accept(com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.describe()); reply.accept(com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.describe()); }
        if (operation.equals("providers")) {
            if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().extensionProviders.forEach(provider -> reply.accept(provider.describe()));
            if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().localSnapshots.forEach(snapshot -> reply.accept(snapshot.describe(System.currentTimeMillis())));
            if (ConnectionGuard.providerHealth().isEmpty()) reply.accept("No provider attempts recorded yet.");
            ConnectionGuard.providerHealth().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> reply.accept(entry.getKey() + ": " + entry.getValue().describe()));
        }
        if (operation.equals("doctor")) for (String line : doctor()) reply.accept(line);
        return true;
    }
    public static List<String> doctor() {
        List<String> lines = new ArrayList<>();
        lines.add("Mode=" + (ConnectionGuard.getSettings().observe ? "OBSERVE" : "ENFORCE")
                + " vpnFailure=" + ConnectionGuard.getSettings().vpnFailure + " geoFailure=" + ConnectionGuard.getSettings().geoFailure);
        lines.add("Lookup deadlineMs=" + ConnectionGuard.getSettings().lookup.deadlineMillis + " httpTimeoutMs=" + ConnectionGuard.getSettings().lookup.httpTimeoutMillis);
        lines.add("Cache=" + (ConnectionGuard.getCacheProvider() == null ? "unavailable" : ConnectionGuard.getCacheProvider().getClass().getSimpleName())
                + "; health requires an actual lookup. This command does not spend API quota.");
        lines.add("Identity declaredForwarding=" + ConnectionGuard.getSettings().trustForwardedIdentity
                + " nativeFloodgate=" + ConnectionGuard.getSettings().nativeFloodgateIdentity
                + "; declared/global trust is not verified temporary-grant authority.");
        lines.addAll(ConnectionGuard.getSettings().warnings);
        if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().localSnapshots.forEach(snapshot -> lines.add(snapshot.describe(System.currentTimeMillis())));
        if (ConnectionGuard.getActiveDraft() != null) ConnectionGuard.getActiveDraft().extensionProviders.forEach(provider -> lines.add(provider.describe()));
        lines.add("Verify client IP forwarding with a controlled client; a public address alone does not establish correct forwarding.");
        lines.add(ConnectionGuard.lookupStats()); lines.add("login-checks active=" + com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.active());
        lines.add(ConnectionGuard.admissionStats());
        lines.add(com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.describe());
        lines.add(com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.describe());
        return lines;
    }
}
