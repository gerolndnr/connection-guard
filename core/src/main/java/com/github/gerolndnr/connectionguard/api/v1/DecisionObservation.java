package com.github.gerolndnr.connectionguard.api.v1;

import java.util.*;

/** The guard's result at one login phase, not a guarantee of final admission by other plugins. */
public final class DecisionObservation {
    public enum Platform { BUKKIT, BUNGEE, VELOCITY }
    public enum Phase { PRE_AUTHENTICATION, LOGIN }
    public enum Mode { OBSERVE, ENFORCE }
    public enum IdentityTrust { UNTRUSTED, AUTHENTICATED, FORWARDED, PLATFORM_ONLINE, FLOODGATE, VERIFIED_FORWARDING }
    public enum Outcome { ALLOW, DENY, ERROR }
    public enum Reason { CHECKS_COMPLETE, FLAG_ALLOWED, UNKNOWN_ALLOWED, ACCESS_RULE, LOOKUP_UNAVAILABLE,
        OVERLOAD, VPN_FLAG, GEO_FLAG, INTERNAL_ERROR, IDENTITY_UNAVAILABLE }
    public enum Check { NOT_CHECKED, EXEMPT, POSITIVE, NEGATIVE, KNOWN, UNKNOWN }
    public enum Flag { ACCESS_POLICY, VPN, GEO }
    public enum Scope { VPN, GEO, ALL }
    public enum Effect { DENY, ALLOW, EXEMPT }
    public enum Match { MATCH, MISS, UNKNOWN, CONFLICT }
    private final Platform platform;
    private final Phase phase;
    private final Mode mode;
    private final IdentityTrust identityTrust;
    private final UUID trustedUuid;
    private final String ip;
    private final Outcome outcome;
    private final Reason reason;
    private final Check vpnCheck, geoCheck;
    private final long observedAt, durationMillis;
    private final boolean processingError;
    private final Set<Flag> flags;
    private final List<Source> sources;
    private final List<Rule> rules;
    public DecisionObservation(Platform platform, Phase phase, Mode mode, IdentityTrust identityTrust,
            UUID uuid, String ip, Outcome outcome, Reason reason, Check vpnCheck, Check geoCheck,
            long observedAt, long durationMillis, boolean processingError, Set<Flag> flags,
            List<Source> sources, List<Rule> rules) {
        this.platform = Objects.requireNonNull(platform); this.phase = Objects.requireNonNull(phase);
        this.mode = Objects.requireNonNull(mode); this.identityTrust = Objects.requireNonNull(identityTrust);
        this.trustedUuid = identityTrust == IdentityTrust.UNTRUSTED ? null : uuid;
        this.ip = com.github.gerolndnr.connectionguard.core.identity.Exemptions.normalize(ip);
        this.outcome = Objects.requireNonNull(outcome); this.reason = Objects.requireNonNull(reason);
        this.vpnCheck = Objects.requireNonNull(vpnCheck); this.geoCheck = Objects.requireNonNull(geoCheck);
        if (observedAt < 0 || durationMillis < 0 || sources.size() > 17 || rules.size() > 1026)
            throw new IllegalArgumentException("Invalid bounded decision observation.");
        if (phase == Phase.PRE_AUTHENTICATION && identityTrust != IdentityTrust.UNTRUSTED)
            throw new IllegalArgumentException("Pre-authentication identity is untrusted.");
        this.observedAt = observedAt; this.durationMillis = durationMillis; this.processingError = processingError;
        EnumSet<Flag> copiedFlags = EnumSet.noneOf(Flag.class); copiedFlags.addAll(flags);
        this.flags = Collections.unmodifiableSet(copiedFlags);
        this.sources = copy(sources); this.rules = copy(rules);
    }
    private static <T> List<T> copy(List<T> values) {
        ArrayList<T> result = new ArrayList<>(values);
        for (T value : result) Objects.requireNonNull(value);
        return Collections.unmodifiableList(result);
    }
    public int getContractVersion() { return ConnectionGuardApi.CONTRACT_VERSION; }
    public Platform getPlatform() { return platform; }
    public Phase getPhase() { return phase; }
    public Mode getMode() { return mode; }
    public IdentityTrust getIdentityTrust() { return identityTrust; }
    public Optional<UUID> getTrustedUuid() { return Optional.ofNullable(trustedUuid); }
    public String getIp() { return ip; }
    public Outcome getOutcome() { return outcome; }
    public Reason getReason() { return reason; }
    public Check getVpnCheck() { return vpnCheck; }
    public Check getGeoCheck() { return geoCheck; }
    public long getObservedAt() { return observedAt; }
    public long getDurationMillis() { return durationMillis; }
    public boolean hasProcessingError() { return processingError; }
    public Set<Flag> getFlags() { return flags; }
    public List<Source> getSources() { return sources; }
    public List<Rule> getRules() { return rules; }
    public List<Effect> getRulePrecedence() { return Collections.unmodifiableList(Arrays.asList(Effect.DENY, Effect.ALLOW, Effect.EXEMPT)); }
    public static final class Source {
        private final String id;
        private final Scope scope;
        private final DetectionObservation observation;
        private final long durationMillis;
        private final boolean voting, fromCache;
        public Source(String id, Scope scope, DetectionObservation observation, long durationMillis, boolean voting, boolean fromCache) {
            if (id == null || !id.matches("[a-zA-Z0-9_#.-]{1,100}") || durationMillis < 0)
                throw new IllegalArgumentException("Invalid decision source.");
            this.id = id; this.scope = Objects.requireNonNull(scope); this.observation = Objects.requireNonNull(observation);
            this.durationMillis = durationMillis; this.voting = voting; this.fromCache = fromCache;
        }
        public String getId() { return id; }
        public Scope getScope() { return scope; }
        public DetectionObservation getObservation() { return observation; }
        public long getDurationMillis() { return durationMillis; }
        public boolean isVoting() { return voting; }
        public boolean isFromCache() { return fromCache; }
    }
    public static final class Rule {
        private final String id;
        private final Scope evaluatedScope;
        private final Effect effect;
        private final Match match;
        private final boolean selected;
        public Rule(String id, Scope evaluatedScope, Effect effect, Match match, boolean selected) {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid decision rule ID.");
            this.id = id; this.evaluatedScope = Objects.requireNonNull(evaluatedScope); this.effect = Objects.requireNonNull(effect);
            this.match = Objects.requireNonNull(match); this.selected = selected;
        }
        public String getId() { return id; }
        public Scope getEvaluatedScope() { return evaluatedScope; }
        public Effect getEffect() { return effect; }
        public Match getMatch() { return match; }
        public boolean isSelected() { return selected; }
    }
}
