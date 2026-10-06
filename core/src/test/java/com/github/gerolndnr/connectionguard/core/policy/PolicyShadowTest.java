package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PolicyShadowTest {
    private static final String IP = "192.0.2.1";
    private final List<AccessRule> rules = Collections.emptyList();
    private final GuardSettings base = settings(true);
    private final GeoLookup geo = new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 0);
    private GuardSettings settings(boolean kick) {
        Map<String, Object> fields = new HashMap<>(); fields.put("behavior.vpn.kick-player", kick);
        return GuardSettings.read(fields::get, Collections.emptyList());
    }
    private void positive(PolicyShadow shadow) {
        VpnResult vpn = new VpnResult(IP, true);
        ConnectionPolicy.Evaluation live = ConnectionPolicy.evaluate(base, rules, IP, null, false, false, false, vpn, geo, null, 100);
        shadow.record(base, rules, 1, IP, null, false, false, false, vpn, geo, null, 100, live);
        assertEquals(Reason.VPN_FLAG, live.denial); assertTrue(vpn.isVpn());
    }
    @Test void disabledByDefaultAndStoppedSessionsNeverEvaluateCandidates() {
        PolicyShadow shadow = new PolicyShadow(); positive(shadow);
        assertEquals(PolicyShadow.State.DISABLED, shadow.view(base, rules, 1).state);
        shadow.start(base, rules, 1, new PolicyReplay.Snapshot(settings(false), rules), 5000);
        positive(shadow); PolicyShadow.View view = shadow.view(base, rules, 1);
        assertEquals(1, view.compared); assertEquals(1, view.changedOutcomes); assertEquals(0, view.changedFlags);
        assertEquals(0, view.changedRules); assertEquals(1, view.unknownGeo);
        shadow.stop(PolicyShadow.State.STOPPED); positive(shadow);
        assertEquals(1, shadow.view(base, rules, 1).compared);
    }
    @Test void candidateDenialIsCountedWithoutChangingTheAllowingLiveResult() {
        GuardSettings allow = settings(false); PolicyShadow shadow = new PolicyShadow();
        shadow.start(allow, rules, 1, new PolicyReplay.Snapshot(base, rules), 5000);
        VpnResult vpn = new VpnResult(IP, true);
        ConnectionPolicy.Evaluation live = ConnectionPolicy.evaluate(allow, rules, IP, null, false, false, false, vpn, geo, null, 100);
        shadow.record(allow, rules, 1, IP, null, false, false, false, vpn, geo, null, 100, live);
        assertNull(live.denial); assertEquals(1, shadow.view(allow, rules, 1).changedOutcomes);
    }
    @Test void exclusiveMonotonicDeadlineAlsoExpiresAnIdleSessionAtStatusRead() {
        AtomicLong clock = new AtomicLong(50); PolicyShadow shadow = new PolicyShadow(clock::get);
        shadow.start(base, rules, 1, new PolicyReplay.Snapshot(base, rules), 5);
        clock.addAndGet(4_999_999); positive(shadow); assertEquals(1, shadow.view(base, rules, 1).compared);
        clock.incrementAndGet(); assertEquals(PolicyShadow.State.EXPIRED, shadow.view(base, rules, 1).state);
        positive(shadow); assertEquals(1, shadow.view(base, rules, 1).compared);
    }
    @Test void modeRuleAndProviderContextChangesStopWithoutMixingPolicies() {
        for (int change = 0; change < 3; change++) {
            PolicyShadow shadow = new PolicyShadow(); shadow.start(base, rules, 1, new PolicyReplay.Snapshot(base, rules), 5000);
            positive(shadow);
            PolicyShadow.View view = shadow.view(change == 0 ? settings(true) : base,
                    change == 1 ? Collections.unmodifiableList(new ArrayList<>()) : rules, change == 2 ? 2 : 1);
            assertEquals(PolicyShadow.State.BASE_CHANGED, view.state); positive(shadow);
            assertEquals(1, shadow.view(base, rules, 1).compared);
        }
    }
    @Test void candidateErrorsAreRedactedAndNeverChangeTheLiveResult() {
        PolicyShadow shadow = new PolicyShadow(); shadow.start(base, rules, 1, new PolicyReplay.Snapshot(base, rules), 5000);
        VpnResult vpn = new VpnResult(IP, true);
        ConnectionPolicy.Evaluation live = ConnectionPolicy.evaluate(base, rules, IP, null, false, false, false, vpn, geo, null, 100);
        shadow.record(base, rules, 1, "198.51.100.1", null, false, false, false, vpn, geo, null, 100, live);
        PolicyShadow.View view = shadow.view(base, rules, 1);
        assertEquals(PolicyShadow.State.EVALUATION_FAILED, view.state); assertEquals(1, view.errors);
        assertEquals(0, view.compared); assertEquals(0, view.pending); assertEquals(Reason.VPN_FLAG, live.denial);
        positive(shadow); assertEquals(1, shadow.view(base, rules, 1).errors);
    }
    @Test void expiredEvidenceAndMissingQuorumStayUnknownInBothEvaluations() {
        PolicyShadow shadow = new PolicyShadow(); shadow.start(base, rules, 1, new PolicyReplay.Snapshot(base, rules), 5000);
        VpnResult vpn = new VpnResult(IP, true); vpn.setPositiveThreshold(2);
        vpn.setVotes(Arrays.asList(new ProviderVote("valid", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1),
                new ProviderVote("expired", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1, DetectionDetails.empty(), 100, null)));
        ConnectionPolicy.Evaluation live = ConnectionPolicy.evaluate(base, rules, IP, null, false, false, false, vpn, geo, null, 100);
        shadow.record(base, rules, 1, IP, null, false, false, false, vpn, geo, null, 100, live);
        PolicyShadow.View view = shadow.view(base, rules, 1);
        assertEquals(1, view.unknownVpn); assertEquals(0, view.changedOutcomes); assertEquals(0, view.changedFlags);
        assertEquals(2, vpn.getPositiveThreshold()); assertTrue(vpn.isVpn());
    }
    @Test void fingerprintIdentifiesPolicyAndExpiryWithoutIncludingOperationalSecrets() {
        PolicyReplay.Snapshot original = new PolicyReplay.Snapshot(base, rules);
        assertEquals(original.fingerprint(), new PolicyReplay.Snapshot(settings(true), rules).fingerprint());
        assertNotEquals(original.fingerprint(), new PolicyReplay.Snapshot(settings(false), rules).fingerprint());
        Map<String, Object> fields = new HashMap<>(); fields.put("behavior.vpn.kick-player", true);
        fields.put("webhook.url", "https://secret.invalid/private");
        assertEquals(original.fingerprint(), new PolicyReplay.Snapshot(GuardSettings.read(fields::get, Collections.emptyList()), rules).fingerprint());
        AccessRule rule = new AccessRule("example", AccessRule.Effect.DENY, AccessRule.Scope.VPN, IP, 100, "Synthetic");
        assertNotEquals(original.fingerprint(), new PolicyReplay.Snapshot(base, Collections.singletonList(rule)).fingerprint());
        assertTrue(original.fingerprint().matches("[0-9a-f]{64}"));
    }
    @Test void startBoundsAndAnAlreadyRunningSessionAreEnforcedBeforeReplacement() {
        PolicyShadow shadow = new PolicyShadow(); PolicyReplay.Snapshot candidate = new PolicyReplay.Snapshot(base, rules);
        for (long duration : new long[]{0, -1, PolicyShadow.MAX_DURATION_MILLIS + 1})
            assertThrows(IllegalArgumentException.class, () -> shadow.start(base, rules, 1, candidate, duration));
        List<AccessRule> many = new ArrayList<>();
        for (int i = 0; i <= PolicyShadow.MAX_RULES; i++) many.add(new AccessRule("test-" + i, AccessRule.Effect.DENY, AccessRule.Scope.VPN, IP, 0, "Synthetic"));
        assertThrows(IllegalArgumentException.class, () -> shadow.start(base, rules, 1, new PolicyReplay.Snapshot(base, many), 5000));
        shadow.start(base, rules, 1, candidate, 5000); positive(shadow);
        assertThrows(IllegalStateException.class, () -> shadow.start(base, rules, 1, candidate, 5000));
        assertEquals(1, shadow.view(base, rules, 1).compared);
    }
    @Test void concurrentSampleLimitIsExactAndNeedsNoQueueOrBackgroundWork() throws Exception {
        PolicyShadow shadow = new PolicyShadow(() -> 0); shadow.start(base, rules, 1, new PolicyReplay.Snapshot(settings(false), rules), 5000);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) tasks.add(pool.submit(() -> { for (int i = 0; i < 2600; i++) positive(shadow); }));
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        PolicyShadow.View view = shadow.view(base, rules, 1);
        assertEquals(PolicyShadow.State.SAMPLE_LIMIT, view.state); assertEquals(PolicyShadow.MAX_SAMPLES, view.compared);
        assertEquals(view.compared, view.changedOutcomes); assertEquals(0, view.pending); assertEquals(0, view.skippedBusy);
    }
    @Test void atMostEightInFlightComparisonsAndBusySamplesAreCountedWithoutBlocking() throws Exception {
        CountDownLatch reserved = new CountDownLatch(PolicyShadow.MAX_PENDING), release = new CountDownLatch(1);
        ThreadLocal<Integer> reads = ThreadLocal.withInitial(() -> 0);
        PolicyShadow shadow = new PolicyShadow(() -> {
            if (Thread.currentThread().getName().startsWith("shadow-bound-")) {
                int call = reads.get() + 1; reads.set(call);
                if (call == 2) {
                    reserved.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Comparison did not release."); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                }
            }
            return 0;
        });
        shadow.start(base, rules, 1, new PolicyReplay.Snapshot(base, rules), 5000);
        ExecutorService pool = Executors.newFixedThreadPool(PolicyShadow.MAX_PENDING, task -> new Thread(task, "shadow-bound-worker"));
        try {
            List<Future<?>> tasks = new ArrayList<>(); for (int i = 0; i < PolicyShadow.MAX_PENDING; i++) tasks.add(pool.submit(() -> positive(shadow)));
            assertTrue(reserved.await(5, TimeUnit.SECONDS)); assertEquals(8, shadow.view(base, rules, 1).pending);
            positive(shadow); assertEquals(1, shadow.view(base, rules, 1).skippedBusy); assertEquals(0, shadow.view(base, rules, 1).compared);
            shadow.stop(PolicyShadow.State.STOPPED); release.countDown(); for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            PolicyShadow.View view = shadow.view(base, rules, 1); assertEquals(8, view.compared); assertEquals(0, view.pending);
            positive(shadow); assertEquals(8, shadow.view(base, rules, 1).compared);
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
