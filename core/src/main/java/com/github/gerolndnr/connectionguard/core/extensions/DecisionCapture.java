package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;

/** Internal per-login capture. Never submits extra lookups or changes a platform result. */
public final class DecisionCapture implements AutoCloseable {
    private final Platform platform;
    private final Phase phase;
    private final String ip, geoSource;
    private final UUID uuid;
    private final IdentityTrust identityTrust;
    private final GuardSettings settings;
    private final long generation, started;
    private Check vpnCheck = Check.NOT_CHECKED, geoCheck = Check.NOT_CHECKED;
    private final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
    private List<AdmissionObservation> admissionChecks=Collections.emptyList();
    private final List<Source> sources = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private Reason denied;
    private long observedAt = System.currentTimeMillis();
    private boolean processingError, finished, overload, unresolved, admissionUnresolved, invalidObservation, identityUnavailable;
    private DecisionCapture(Platform platform, Phase phase, String ip, UUID uuid, IdentityTrust trust, long startedNanos) {
        this.started = startedNanos;
        this.platform = platform; this.phase = phase; this.ip = ip; this.uuid = uuid; this.identityTrust = trust;
        settings = ConnectionGuard.getSettings(); generation = DecisionObservers.captureGeneration();
        geoSource = "geo." + (ConnectionGuard.getGeoProvider() == null ? "none" : ConnectionGuard.getGeoProvider().getClass().getSimpleName());
    }
    public static DecisionCapture begin(Platform platform, Phase phase, String ip, UUID uuid, IdentityTrust trust) {
        return begin(platform, phase, ip, uuid, trust, System.nanoTime());
    }
    /** Includes connection/native-identity inspection in the caller's existing whole-login budget. */
    public static DecisionCapture begin(Platform platform, Phase phase, String ip, UUID uuid, IdentityTrust trust, long startedNanos) {
        synchronized (ConnectionGuard.class) { return new DecisionCapture(platform, phase, ip, uuid, trust, startedNanos); }
    }
    public long startedNanos() { return started; }
    public boolean observe() { return settings.observe; }
    public GuardSettings settings() { return settings; }
    public void admission(List<AdmissionObservation> values){record(()->{admissionChecks=Collections.unmodifiableList(new ArrayList<>(values)); admissionUnresolved=values.stream().anyMatch(v->v.getResponse().getStatus()==AdmissionResponse.Status.UNKNOWN);});}
    public void denied(Reason reason) { denied = reason; }
    public void error() { processingError = true; }
    public void identityUnavailable() { identityUnavailable = true; }
    public void overload() { overload = true; }
    public void flag(Flag flag) { if (generation >= 0) flags.add(flag); }
    public void manual(Optional<AccessRule> vpn, Optional<AccessRule> geo) {
        record(() -> { rules.clear(); vpn.ifPresent(rule -> selected(rule, Scope.VPN)); geo.ifPresent(rule -> selected(rule, Scope.GEO)); });
    }
    private void selected(AccessRule rule, Scope scope) { selected(rule, scope, Match.MATCH); }
    private void selected(AccessRule rule, Scope scope, Match match) {
        rules.add(new Rule(rule.getId(), scope, Effect.valueOf(rule.getEffect().name()), match, true));
        if (rule.getEffect() == AccessRule.Effect.DENY) flags.add(Flag.ACCESS_POLICY);
    }
    public void policy(EvidencePolicy.Decision vpn, EvidencePolicy.Decision geo) {
        record(() -> {
            unresolved = vpn.isUnresolved() || geo.isUnresolved(); flags.remove(Flag.ACCESS_POLICY);
            rules.clear(); policy(vpn, Scope.VPN); policy(geo, Scope.GEO);
        });
    }
    private void policy(EvidencePolicy.Decision decision, Scope scope) {
        decision.getRule().ifPresent(rule -> selected(rule, scope, decision.getTrace().stream()
                .filter(entry -> entry.getRule().getId().equals(rule.getId())).map(entry -> Match.valueOf(entry.getMatch().name()))
                .findFirst().orElse(Match.MATCH)));
        for (EvidencePolicy.Evaluation evaluation : decision.getTrace()) {
            AccessRule rule = evaluation.getRule();
            rules.add(new Rule(rule.getId(), scope, Effect.valueOf(rule.getEffect().name()),
                    Match.valueOf(evaluation.getMatch().name()), false));
        }
    }
    public void facts(VpnResult vpn, GeoLookup geo, boolean vpnExempt, boolean geoExempt, long asOf) {
        record(() -> {
            observedAt = asOf;
            sources.clear();
            vpnCheck = vpnExempt ? Check.EXEMPT : Check.valueOf(vpn.getStatus().name());
            geoCheck = geoExempt ? Check.EXEMPT : geo.getResult().isPresent() ? Check.KNOWN : Check.UNKNOWN;
            if (!vpnExempt) for (ProviderVote vote : vpn.getVotes()) sources.add(new Source(vote.getProvider(), Scope.VPN,
                    new DetectionObservation(DetectionObservation.Status.valueOf(vote.getStatus().name()),
                            DetectionObservation.Reason.valueOf(vote.getReason().name()), metadata(vote.getDetails()),
                            vote.getValidUntil(), vote.getSourceVersion()), vote.getDurationMillis(), vote.isVoting(), vpn.isFromCache()));
            if (!geoExempt) {
                GeoResult value = geo.getResult().orElse(null);
                DetectionMetadata details = value == null ? DetectionMetadata.empty() : new DetectionMetadata(null,
                        value.getAsn(), "Unknown".equalsIgnoreCase(value.getIspName()) ? null : value.getIspName(), null,
                        value.getCountryName().matches("[A-Z]{2}") ? value.getCountryName() : null, null, null);
                sources.add(new Source(geoSource, Scope.GEO, new DetectionObservation(
                        value == null ? DetectionObservation.Status.UNKNOWN : DetectionObservation.Status.NEGATIVE,
                        value != null ? DetectionObservation.Reason.NONE : geo.getReason() == FailureReason.NONE
                                ? DetectionObservation.Reason.NO_EVIDENCE : DetectionObservation.Reason.valueOf(geo.getReason().name()),
                        details, value == null ? 0 : value.getValidUntil(), value == null ? null : value.getSourceVersion()),
                        geo.getDurationMillis(), false, geo.isCached()));
            }
        });
    }
    private void record(Runnable work) {
        if (generation < 0 || invalidObservation) return;
        try { work.run(); }
        catch (RuntimeException | LinkageError invalid) { invalidObservation = true; DecisionObservers.recordFailure(); }
    }
    private static DetectionMetadata metadata(DetectionDetails details) {
        Map<DetectionMetadata.Type, Boolean> values = new EnumMap<>(DetectionMetadata.Type.class);
        details.getClassifications().forEach((type, flag) -> values.put(DetectionMetadata.Type.valueOf(type.name()), flag));
        return DetectionMetadata.withExactRisk(values, details.getAsn(), details.getIsp(), details.getOperator(),
                details.getCountry(), details.getExactRisk(), details.getConfidence());
    }
    @Override public synchronized void close() {
        if (finished) return;
        finished = true;
        if (generation < 0 || invalidObservation) return;
        // Observation construction must never change admission or retain exception details.
        try {
            Outcome outcome = denied != null ? Outcome.DENY : processingError ? Outcome.ERROR : Outcome.ALLOW;
            Reason reason = denied != null ? denied : identityUnavailable ? Reason.IDENTITY_UNAVAILABLE : processingError ? Reason.INTERNAL_ERROR : overload ? Reason.OVERLOAD
                    : !flags.isEmpty() ? Reason.FLAG_ALLOWED : admissionUnresolved || unresolved || vpnCheck == Check.UNKNOWN || geoCheck == Check.UNKNOWN
                    ? Reason.UNKNOWN_ALLOWED : Reason.CHECKS_COMPLETE;
            DecisionObservers.publish(new DecisionObservation(platform, phase,
                    observe() ? Mode.OBSERVE : Mode.ENFORCE, identityTrust, uuid, ip, outcome, reason, vpnCheck, geoCheck,
                    observedAt, Math.max(0, (System.nanoTime() - started) / 1000000), processingError,
                    flags, sources, rules, admissionChecks), generation);
        } catch (RuntimeException | LinkageError invalid) {
            // No endpoint, provider payload, exception message, player object or secret is emitted.
            DecisionObservers.recordFailure();
        }
    }
}
