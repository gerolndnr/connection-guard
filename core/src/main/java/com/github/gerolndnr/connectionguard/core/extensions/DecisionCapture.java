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
    private final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages;
    private final long generation, started;
    private final boolean captureEnabled;
    private com.github.gerolndnr.connectionguard.core.webhook.WebhookContext webhookContext = com.github.gerolndnr.connectionguard.core.webhook.WebhookContext.EMPTY;
    private final int positiveThreshold;
    private final boolean geoDisabled;
    private final com.github.gerolndnr.connectionguard.core.policy.DecisionLeases.Lease policyLease;
    private Check vpnCheck = Check.NOT_CHECKED, geoCheck = Check.NOT_CHECKED;
    private final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
    private List<AdmissionObservation> admissionChecks=Collections.emptyList();
    private final List<Source> sources = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private Reason denied;
    private FailureReason uncheckedVpnReason;
    private long observedAt = System.currentTimeMillis();
    private boolean processingError, finished, overload, unresolved, admissionUnresolved, invalidObservation, identityUnavailable;
    private DecisionCapture(Platform platform, Phase phase, String ip, UUID uuid, IdentityTrust trust, long startedNanos) {
        this.started = startedNanos;
        this.platform = platform; this.phase = phase; this.ip = ip; this.uuid = uuid; this.identityTrust = trust;
        settings = ConnectionGuard.getSettings(); messages = ConnectionGuard.getMessages(); generation = DecisionObservers.captureGeneration();
        captureEnabled = generation >= 0 || !settings.observe && (settings.webhooks.hasEmbeds() || settings.webhooks.hasText());
        positiveThreshold = ConnectionGuard.getRequiredPositiveFlags();
        geoDisabled = ConnectionGuard.isGeoDisabled();
        String selectedGeo = ConnectionGuard.policyGeoSource();
        geoSource = selectedGeo == null ? "geo.none" : selectedGeo;
        policyLease = ConnectionGuard.acquireDecisionLease();
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
    public String policyGeoSource() { return geoSource.equals("geo.none") ? null : geoSource; }
    public com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages() { return messages; }
    public void playerName(String name) {
        if(!settings.observe && settings.webhooks.hasText()) record(()->webhookContext=webhookContext.withPlayerName(name));
    }
    public void admission(List<AdmissionObservation> values){record(()->{admissionChecks=Collections.unmodifiableList(new ArrayList<>(values)); admissionUnresolved=values.stream().anyMatch(v->v.getResponse().getStatus()==AdmissionResponse.Status.UNKNOWN);});}
    public void denied(Reason reason) { denied = reason; }
    public void error() { processingError = true; }
    public void identityUnavailable() { identityUnavailable = true; }
    public void overload() { overload(false); }
    public void overload(boolean vpnExempt) { overload = true; if (!vpnExempt) uncheckedVpnReason = FailureReason.OVERLOADED; }
    public void flag(Flag flag) { if (captureEnabled) flags.add(flag); }
    public void manual(Optional<AccessRule> vpn, Optional<AccessRule> geo) {
        record(() -> { rules.clear(); vpn.ifPresent(rule -> selected(rule, Scope.VPN)); geo.ifPresent(rule -> selected(rule, Scope.GEO)); });
    }
    private void selected(AccessRule rule, Scope scope) { selected(rule, scope, Match.MATCH); }
    private void selected(AccessRule rule, Scope scope, Match match) {
        rules.add(new Rule(rule.getId(), scope, Effect.valueOf(rule.getEffect().name()), match, true));
        if (rule.getEffect() == AccessRule.Effect.DENY) flags.add(Flag.ACCESS_POLICY);
    }
    public void policy(EvidencePolicy.Decision vpn, EvidencePolicy.Decision geo) {
        if (vpn.isBypassed()) uncheckedVpnReason = null;
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
        uncheckedVpnReason = UncheckedVpnAdmissions.unresolved(vpn, vpnExempt);
        record(() -> {
            observedAt = asOf;
            sources.clear();
            vpnCheck = vpnExempt ? Check.EXEMPT : Check.valueOf(vpn.getStatus().name());
            geoCheck = geoExempt ? Check.EXEMPT : geoDisabled ? Check.NOT_CHECKED : geo.getResult().isPresent() ? Check.KNOWN : Check.UNKNOWN;
            if (!vpnExempt) for (ProviderVote vote : vpn.getVotes()) sources.add(new Source(vote.getProvider(), Scope.VPN,
                    new DetectionObservation(DetectionObservation.Status.valueOf(vote.getStatus().name()),
                            DetectionObservation.Reason.valueOf(vote.getReason().name()), metadata(vote.getDetails()),
                            vote.getValidUntil(), vote.getSourceVersion()), vote.getDurationMillis(), vote.isVoting(), vpn.isFromCache()));
            if (!geoExempt && !geoDisabled) {
                GeoResult value = geo.getResult().orElse(null);
                if(!settings.observe && settings.webhooks.hasText() && value!=null)
                    webhookContext=webhookContext.withLocation(value.getCountryName(),value.getCityName(),value.getIspName());
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
        if (!captureEnabled || invalidObservation) return;
        try { work.run(); }
        catch (RuntimeException | LinkageError invalid) { invalidObservation = true; if(generation>=0)DecisionObservers.recordFailure();
            com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.recordInvalid(); }
    }
    private static DetectionMetadata metadata(DetectionDetails details) {
        Map<DetectionMetadata.Type, Boolean> values = new EnumMap<>(DetectionMetadata.Type.class);
        details.getClassifications().forEach((type, flag) -> values.put(DetectionMetadata.Type.valueOf(type.name()), flag));
        return DetectionMetadata.withExactRisk(values, details.getAsn(), details.getIsp(), details.getOperator(),
                details.getCountry(), details.getExactRisk(), details.getConfidence()).withDataAsOf(details.getDataAsOf());
    }
    @Override public synchronized void close() {
        if (finished) return;
        finished = true;
        if (denied == null && !processingError) ConnectionGuard.uncheckedVpnAdmissions().allowed(uncheckedVpnReason);
        policyLease.close();
        if (!captureEnabled || invalidObservation) return;
        final long duration=Math.max(0,(System.nanoTime()-started)/1000000);
        final EnumSet<Flag> capturedFlags=flags.clone();
        final List<Source> capturedSources=new ArrayList<>(sources);
        final List<Rule> capturedRules=new ArrayList<>(rules);
        final Outcome capturedOutcome=denied!=null?Outcome.DENY:processingError?Outcome.ERROR:Outcome.ALLOW;
        final Reason capturedReason=denied!=null?denied:identityUnavailable?Reason.IDENTITY_UNAVAILABLE:processingError?Reason.INTERNAL_ERROR:overload?Reason.OVERLOAD
            :!flags.isEmpty()?Reason.FLAG_ALLOWED:admissionUnresolved || unresolved || vpnCheck==Check.UNKNOWN || geoCheck==Check.UNKNOWN?Reason.UNKNOWN_ALLOWED:Reason.CHECKS_COMPLETE;
        final Check capturedVpn=vpnCheck,capturedGeo=geoCheck;
        final long capturedAt=observedAt;
        final boolean capturedError=processingError;
        final List<AdmissionObservation> capturedAdmission=admissionChecks;
        final com.github.gerolndnr.connectionguard.core.webhook.WebhookContext capturedWebhookContext=webhookContext;
        DecisionObservers.deferCapture(()->emit(capturedOutcome,capturedReason,capturedVpn,capturedGeo,capturedAt,duration,capturedError,capturedFlags,capturedSources,capturedRules,capturedAdmission,capturedWebhookContext));
    }
    private void emit(Outcome outcome,Reason reason,Check vpnCheck,Check geoCheck,long observedAt,long duration,boolean processingError,
            Set<Flag> flags,List<Source> sources,List<Rule> rules,List<AdmissionObservation> admissionChecks,
            com.github.gerolndnr.connectionguard.core.webhook.WebhookContext webhookContext){
        // Observation construction must never change admission or retain exception details.
        DecisionObservation event;
        try {
            event = new DecisionObservation(platform, phase,
                    observe() ? Mode.OBSERVE : Mode.ENFORCE, identityTrust, uuid, ip, outcome, reason, vpnCheck, geoCheck,
                    observedAt, duration, processingError,
                    flags, sources, rules, admissionChecks);
        } catch (RuntimeException | LinkageError invalid) {
            if (generation >= 0) DecisionObservers.recordFailure();
            com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.recordInvalid();
            return;
        }
        try { com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.sendDecision(event,settings.webhooks,positiveThreshold,messages,webhookContext); }
        catch (RuntimeException | LinkageError invalid) { com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.recordInvalid(); }
        // A notification failure must not prevent a separately selected observer from receiving facts.
        if (generation >= 0) DecisionObservers.publish(event,generation);
    }
}
