package com.github.gerolndnr.connectionguard.core.rules;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.*;

/** Pure evaluation: no lookup, storage write, kick, notification or other side effect. */
public final class EvidencePolicy {
    private EvidencePolicy() { }
    public enum Match { MATCH, MISS, UNKNOWN, CONFLICT }
    public static final class Evaluation {
        private final AccessRule rule;
        private final Match match;
        private Evaluation(AccessRule rule, Match match) { this.rule = rule; this.match = match; }
        public AccessRule getRule() { return rule; }
        public Match getMatch() { return match; }
    }
    public static final class Decision {
        private final AccessRule rule;
        private final List<Evaluation> trace;
        private Decision(AccessRule rule, List<Evaluation> trace) { this.rule = rule; this.trace = Collections.unmodifiableList(trace); }
        public Optional<AccessRule> getRule() { return Optional.ofNullable(rule); }
        public List<Evaluation> getTrace() { return trace; }
        public boolean isDenied() { return rule != null && rule.getEffect() == AccessRule.Effect.DENY; }
        public boolean isBypassed() { return rule != null && rule.getEffect() != AccessRule.Effect.DENY; }
        public boolean isUnresolved() { return rule == null && trace.stream().anyMatch(entry -> entry.match == Match.UNKNOWN || entry.match == Match.CONFLICT); }
        public String describe() { return rule == null ? "rule=none unresolved=" + isUnresolved() : "rule=" + rule.getId() + " effect=" + rule.getEffect(); }
    }
    public static Decision evaluate(List<AccessRule> rules, String ip, UUID uuid, boolean trusted, AccessRule.Scope scope,
                                    List<ProviderVote> sources, long now) {
        // Explicit address/identity overrides precede metadata, which is provider evidence rather than identity.
        for (AccessRule.Effect effect : precedence()) for (AccessRule rule : rules)
            if (rule.getEffect() == effect && rule.matches(ip, uuid, trusted, scope, now)) return new Decision(rule, Collections.emptyList());
        List<Evaluation> trace = new ArrayList<>();
        for (AccessRule rule : rules) if (rule.isMetadata() && rule.active(scope, now)) trace.add(new Evaluation(rule, match(rule, sources)));
        for (AccessRule.Effect effect : precedence()) {
            // Metadata cannot grant access around an unresolved higher-priority deny.
            if (effect != AccessRule.Effect.DENY && trace.stream().anyMatch(entry -> entry.rule.getEffect() == AccessRule.Effect.DENY && entry.match == Match.UNKNOWN)) return new Decision(null, trace);
            for (Evaluation entry : trace) if (entry.rule.getEffect() == effect && (entry.match == Match.MATCH
                    || effect == AccessRule.Effect.DENY && entry.match == Match.CONFLICT)) return new Decision(entry.rule, trace);
        }
        return new Decision(null, trace);
    }
    private static AccessRule.Effect[] precedence() { return new AccessRule.Effect[]{AccessRule.Effect.DENY, AccessRule.Effect.ALLOW, AccessRule.Effect.EXEMPT}; }
    static Match match(AccessRule rule, List<ProviderVote> sources) {
        int yes = 0, no = 0, unknown = 0, selected = 0;
        String value = rule.value();
        boolean score = rule.getType() == AccessRule.Target.RISK || rule.getType() == AccessRule.Target.CONFIDENCE;
        String source = score ? value.substring(0, value.lastIndexOf(':')) : null;
        for (ProviderVote vote : sources) {
            if (score && !vote.getProvider().equals(source)) continue;
            if (vote.getProvider().startsWith("geo.") && (rule.getType() == AccessRule.Target.TYPE || rule.getType() == AccessRule.Target.OPERATOR)) continue;
            selected++;
            if (vote.getStatus() == ProviderVote.Status.UNKNOWN) { unknown++; continue; }
            Boolean answer = compare(rule, vote.getDetails());
            if (answer == null) unknown++; else if (answer) yes++; else no++;
        }
        if (selected == 0 || yes == 0 && no == 0) return Match.UNKNOWN;
        if (yes > 0 && no > 0) return Match.CONFLICT;
        if (rule.getEffect() == AccessRule.Effect.DENY && yes > 0) return Match.MATCH;
        if (unknown > 0) return Match.UNKNOWN;
        return yes > 0 ? Match.MATCH : Match.MISS;
    }
    private static Boolean compare(AccessRule rule, DetectionDetails details) {
        String value = rule.value();
        switch (rule.getType()) {
            case ASN: return details.getAsn() == null ? null : details.getAsn() == Long.parseLong(value.toUpperCase(Locale.ROOT).replace("AS", ""));
            case ISP: return details.getIsp() == null ? null : details.getIsp().equalsIgnoreCase(value);
            case OPERATOR: return details.getOperator() == null ? null : details.getOperator().equalsIgnoreCase(value);
            case COUNTRY: return details.getCountry() == null ? null : details.getCountry().equals(value);
            case TYPE: return details.get(DetectionDetails.Type.valueOf(value.toUpperCase(Locale.ROOT)));
            case RISK: case CONFIDENCE:
                Integer number = rule.getType() == AccessRule.Target.RISK ? details.getRisk() : details.getConfidence();
                return number == null ? null : number >= Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
            default: throw new IllegalArgumentException("Not a metadata selector.");
        }
    }
}
