package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Explicitly enabled, bounded comparison of existing live facts. Retains counters, never observed identities. */
public final class PolicyShadow {
    public static final int MAX_SAMPLES = 10_000, MAX_PENDING = 8, MAX_RULES = 128;
    public static final long MAX_DURATION_MILLIS = TimeUnit.HOURS.toMillis(1);
    public enum State { DISABLED, ACTIVE, STOPPED, EXPIRED, SAMPLE_LIMIT, BASE_CHANGED, EVALUATION_FAILED }
    private final LongSupplier clock;
    private volatile Session current;
    public PolicyShadow() { this(System::nanoTime); }
    PolicyShadow(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public synchronized View start(GuardSettings base, List<AccessRule> baseRules, long epoch,
                                   PolicyReplay.Snapshot candidate, long durationMillis) {
        if (durationMillis < 1 || durationMillis > MAX_DURATION_MILLIS || candidate.rules.size() > MAX_RULES)
            throw new IllegalArgumentException("Invalid bounded shadow session (values redacted).");
        if (current != null && current.view(base, baseRules, epoch).state == State.ACTIVE)
            throw new IllegalStateException("A shadow session is already active.");
        current = new Session(base, baseRules, epoch, candidate, clock.getAsLong(), durationMillis);
        return current.view(base, baseRules, epoch);
    }
    public View view(GuardSettings base, List<AccessRule> baseRules, long epoch) {
        Session session = current;
        return session == null ? View.disabled() : session.view(base, baseRules, epoch);
    }
    public void stop(State reason) {
        if (reason == State.ACTIVE || reason == State.DISABLED) throw new IllegalArgumentException("Invalid stop reason.");
        Session session = current; if (session != null) session.stop(reason);
    }
    public void record(GuardSettings selected, List<AccessRule> selectedRules, long epoch, String ip, UUID uuid,
                       boolean trusted, boolean vpnExempt, boolean geoExempt, VpnResult vpn, GeoLookup geo,
                       String geoSource, long asOf, ConnectionPolicy.Evaluation live) {
        Session session = current;
        if (session == null || !session.reserve(selected, selectedRules, epoch)) return;
        long started = clock.getAsLong();
        try {
            ConnectionPolicy.Evaluation candidate = ConnectionPolicy.evaluate(session.candidate.settings, session.candidate.rules,
                    ip, uuid, trusted, vpnExempt, geoExempt, vpn, geo, geoSource, asOf);
            session.finish(live, candidate, clock.getAsLong() - started);
        } catch (RuntimeException invalid) { session.fail(); /* Candidate failure must never alter live admission. */ }
    }
    public static final class View {
        public final State state;
        public final String baseId, candidateId;
        public final int compared, changedOutcomes, changedFlags, changedRules, unknownVpn, unknownGeo, pending, skippedBusy, errors;
        public final long remainingMillis, evaluationNanos, maxEvaluationNanos;
        private View(State state, String baseId, String candidateId, int compared, int changedOutcomes, int changedFlags,
                     int changedRules, int unknownVpn, int unknownGeo, int pending, int skippedBusy, int errors,
                     long remainingMillis, long evaluationNanos, long maxEvaluationNanos) {
            this.state = state; this.baseId = baseId; this.candidateId = candidateId; this.compared = compared;
            this.changedOutcomes = changedOutcomes; this.changedFlags = changedFlags; this.changedRules = changedRules;
            this.unknownVpn = unknownVpn; this.unknownGeo = unknownGeo; this.pending = pending;
            this.skippedBusy = skippedBusy; this.errors = errors; this.remainingMillis = remainingMillis;
            this.evaluationNanos = evaluationNanos; this.maxEvaluationNanos = maxEvaluationNanos;
        }
        private static View disabled() { return new View(State.DISABLED, "none", "none", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
    }
    private final class Session {
        private final GuardSettings base;
        private final List<AccessRule> baseRules;
        private final long epoch, started, durationNanos;
        private final PolicyReplay.Snapshot candidate;
        private final String baseId, candidateId;
        private volatile State state = State.ACTIVE;
        private int reserved, compared, changedOutcomes, changedFlags, changedRules, unknownVpn, unknownGeo, pending, skippedBusy, errors;
        private long evaluationNanos, maxEvaluationNanos;
        private Session(GuardSettings base, List<AccessRule> baseRules, long epoch, PolicyReplay.Snapshot candidate, long started, long durationMillis) {
            this.base = base; this.baseRules = baseRules; this.epoch = epoch; this.candidate = candidate;
            this.started = started; durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis);
            baseId = new PolicyReplay.Snapshot(base, baseRules).fingerprint(); candidateId = candidate.fingerprint();
        }
        private void validate(GuardSettings selected, List<AccessRule> rules, long currentEpoch) {
            if (state != State.ACTIVE) return;
            if (selected != base || rules != baseRules || currentEpoch != epoch) state = State.BASE_CHANGED;
            else if (clock.getAsLong() - started >= durationNanos) state = State.EXPIRED;
            else if (reserved >= MAX_SAMPLES) state = State.SAMPLE_LIMIT;
        }
        private synchronized boolean reserve(GuardSettings selected, List<AccessRule> rules, long currentEpoch) {
            validate(selected, rules, currentEpoch); if (state != State.ACTIVE) return false;
            if (pending >= MAX_PENDING) { if (skippedBusy < Integer.MAX_VALUE) skippedBusy++; return false; }
            reserved++; pending++; return true;
        }
        private synchronized void finish(ConnectionPolicy.Evaluation live, ConnectionPolicy.Evaluation next, long nanos) {
            pending--; compared++;
            if (!Objects.equals(live.denial, next.denial)) changedOutcomes++;
            if (live.vpnFlag != next.vpnFlag || live.geoFlag != next.geoFlag) changedFlags++;
            if (!selected(live.vpnRule).equals(selected(next.vpnRule)) || !selected(live.geoRule).equals(selected(next.geoRule))) changedRules++;
            if (live.vpn.getStatus() == ProviderVote.Status.UNKNOWN) unknownVpn++;
            if (live.geo.getReason() != FailureReason.NONE) unknownGeo++;
            evaluationNanos += Math.max(0, nanos); maxEvaluationNanos = Math.max(maxEvaluationNanos, Math.max(0, nanos));
        }
        private synchronized void fail() { pending--; errors++; if (state == State.ACTIVE) state = State.EVALUATION_FAILED; }
        private synchronized void stop(State reason) { if (state == State.ACTIVE) state = reason; }
        private synchronized View view(GuardSettings selected, List<AccessRule> rules, long currentEpoch) {
            validate(selected, rules, currentEpoch);
            return new View(state, baseId, candidateId, compared, changedOutcomes, changedFlags, changedRules, unknownVpn,
                    unknownGeo, pending, skippedBusy, errors, state == State.ACTIVE
                    ? Math.max(0, TimeUnit.NANOSECONDS.toMillis(durationNanos - (clock.getAsLong() - started))) : 0,
                    evaluationNanos, maxEvaluationNanos);
        }
    }
    private static String selected(EvidencePolicy.Decision decision) {
        return decision.getRule().map(rule -> rule.getId() + "/" + rule.getEffect()).orElse(decision.isUnresolved() ? "unresolved" : "none");
    }
}
