package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DecisionObserversTest {
    @BeforeEach void initialize() {
        DecisionObservers.closeAll(); ConnectionGuard.applySettings(GuardSettings.defaults()); ConnectionGuard.setGeoProvider(null);
    }
    @AfterEach void cleanup() { DecisionObservers.closeAll(); ConnectionGuard.applySettings(GuardSettings.defaults()); }
    private ObserverSettings settings(String... ids) {
        Map<String, Object> value = new HashMap<>(); value.put("integrations.observers.enabled", ids.length > 0);
        value.put("integrations.observers.ids", Arrays.asList(ids)); return new ObserverSettings(value::get);
    }
    private DecisionObservation sample() {
        return new DecisionObservation(Platform.VELOCITY, Phase.LOGIN, Mode.ENFORCE, IdentityTrust.UNTRUSTED,
                UUID.randomUUID(), "2001:db8::1", Outcome.ALLOW, Reason.CHECKS_COMPLETE, Check.NEGATIVE, Check.EXEMPT,
                1000, 10, false, Collections.emptySet(), Collections.emptyList(), Collections.emptyList());
    }
    private CompletableFuture<DecisionObservation> observer(String id) {
        CompletableFuture<DecisionObservation> next = new CompletableFuture<>();
        ConnectionGuardApi.registerDecisionObserver(id, next::complete); return next;
    }
    @Test void connectionInspectionTimeBelongsToTheWholeLoginAndObservationDuration() throws Exception {
        CompletableFuture<DecisionObservation> next=observer("fixture");DecisionObservers.configure(settings("fixture"));
        long entered=System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(90);
        DecisionCapture capture=DecisionCapture.begin(Platform.VELOCITY,Phase.LOGIN,"192.0.2.1",UUID.randomUUID(),IdentityTrust.FLOODGATE,entered);
        assertEquals(entered,capture.startedNanos());capture.close();
        assertTrue(next.get(2,TimeUnit.SECONDS).getDurationMillis()>=90);
    }
    @Test void observeReportsLostIdentityAuthorityWithoutClaimingSuccessfulChecks() throws Exception {
        Map<String,Object> fields=new HashMap<>();fields.put("operation.mode","OBSERVE");
        ConnectionGuard.applySettings(GuardSettings.read(fields::get,Collections.emptyList()));
        CompletableFuture<DecisionObservation> next=observer("fixture");DecisionObservers.configure(settings("fixture"));
        DecisionCapture capture=DecisionCapture.begin(Platform.VELOCITY,Phase.LOGIN,"192.0.2.1",UUID.randomUUID(),IdentityTrust.FLOODGATE);
        capture.identityUnavailable();capture.close();DecisionObservation event=next.get(2,TimeUnit.SECONDS);
        assertEquals(Outcome.ALLOW,event.getOutcome());assertEquals(Reason.IDENTITY_UNAVAILABLE,event.getReason());
        assertEquals(Check.NOT_CHECKED,event.getVpnCheck());
    }
    @Test void registrationAndLateInstallationRequireExplicitSelectionAndReload() throws Exception {
        CompletableFuture<DecisionObservation> next = observer("fixture");
        DecisionObservers.publish(sample()); assertFalse(next.isDone());
        DecisionObservers.configure(settings("missing")); DecisionObservers.publish(sample()); assertFalse(next.isDone());
        CompletableFuture<DecisionObservation> late = observer("missing");
        DecisionObservers.publish(sample()); assertFalse(late.isDone());
        DecisionObservers.configure(settings("missing")); DecisionObservers.publish(sample());
        assertEquals(1, late.get(2, TimeUnit.SECONDS).getContractVersion()); assertFalse(next.isDone());
    }
    @Test void callbacksRunOutsideCallerAndFailuresDoNotAlterOrPreventOtherDelivery() throws Exception {
        long failed = DecisionObservers.failed(); CompletableFuture<String> thread = new CompletableFuture<>();
        ConnectionGuardApi.registerDecisionObserver("bad", event -> { throw new IllegalStateException("secret must not be logged"); });
        ConnectionGuardApi.registerDecisionObserver("good", event -> thread.complete(Thread.currentThread().getName()));
        DecisionObservers.configure(settings("bad", "good")); DecisionObservers.publish(sample());
        assertTrue(thread.get(2, TimeUnit.SECONDS).startsWith("ConnectionGuard-observer-"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (DecisionObservers.failed() == failed && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(DecisionObservers.failed() > failed);
        assertFalse(DecisionObservers.describe().contains("secret"));
    }
    @Test void closedAndReplacedHandlesDoNotActivateOrRemoveTheReplacement() throws Exception {
        AtomicInteger oldCalls = new AtomicInteger();
        ObserverRegistration old = ConnectionGuardApi.registerDecisionObserver("fixture", event -> oldCalls.incrementAndGet());
        DecisionObservers.configure(settings("fixture")); old.close();
        CompletableFuture<DecisionObservation> next = new CompletableFuture<>();
        ObserverRegistration replacement = ConnectionGuardApi.registerDecisionObserver("fixture", next::complete);
        old.close(); assertTrue(replacement.isRegistered()); DecisionObservers.publish(sample()); assertFalse(next.isDone());
        DecisionObservers.configure(settings("fixture")); DecisionObservers.publish(sample()); next.get(2, TimeUnit.SECONDS);
        assertEquals(0, oldCalls.get());
    }
    @Test void slowCallbacksHaveFiniteQueueAndCannotRunOnThePublishingThread() throws Exception {
        CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ConnectionGuardApi.registerDecisionObserver("slow", event -> { calls.incrementAndGet(); running.countDown(); release.await(); });
        DecisionObservers.configure(settings("slow")); DecisionObservers.publish(sample()); DecisionObservers.publish(sample());
        try {
            assertTrue(running.await(2, TimeUnit.SECONDS)); long dropped = DecisionObservers.dropped(), start = System.nanoTime();
            for (int i = 0; i < 80; i++) DecisionObservers.publish(sample());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
            assertTrue(DecisionObservers.dropped() >= dropped + 16); assertTrue(DecisionObservers.describe().contains("queued=64"));
            DecisionObservers.configure(settings()); assertTrue(DecisionObservers.describe().contains("queued=0"));
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }
    @Test void capturedOldGenerationIsNotDeliveredToNewlySelectedCode() {
        CompletableFuture<DecisionObservation> old = observer("old"), next = observer("next");
        DecisionObservers.configure(settings("old")); long generation = DecisionObservers.captureGeneration();
        DecisionObservers.configure(settings("next")); DecisionObservers.publish(sample(), generation);
        assertFalse(old.isDone()); assertFalse(next.isDone());
    }
    @Test void immutableCollectionsNeverExposeUntrustedUuidOrMutableCallerData() {
        List<Source> sources = new ArrayList<>(); List<Rule> rules = new ArrayList<>(); EnumSet<Flag> flags = EnumSet.of(Flag.VPN);
        sources.add(new Source("fixture", Scope.VPN, DetectionObservation.negative(DetectionMetadata.empty()), 1, true, false));
        DecisionObservation event = new DecisionObservation(Platform.VELOCITY, Phase.LOGIN, Mode.OBSERVE, IdentityTrust.UNTRUSTED,
                UUID.randomUUID(), "192.0.2.1", Outcome.ALLOW, Reason.FLAG_ALLOWED, Check.POSITIVE, Check.EXEMPT,
                1000, 10, false, flags, sources, rules);
        flags.clear(); sources.clear(); assertEquals(1, event.getSources().size()); assertEquals(1, event.getFlags().size());
        assertFalse(event.getTrustedUuid().isPresent()); assertThrows(UnsupportedOperationException.class, () -> event.getSources().clear());
        assertThrows(UnsupportedOperationException.class, () -> event.getFlags().clear());
        assertEquals(Arrays.asList(Effect.DENY, Effect.ALLOW, Effect.EXEMPT), event.getRulePrecedence());
        assertThrows(UnsupportedOperationException.class, () -> event.getRulePrecedence().clear());
    }
    @Test void selectionIsStrictBoundedAndDoesNotInvalidateDetectionFactNamespace() {
        Map<String, Object> value = new HashMap<>(); value.put("provider.geo.service", "Disabled");
        ProviderConfiguration before = new ProviderConfiguration(value::get, Collections.emptyList());
        value.put("integrations.observers.enabled", true); value.put("integrations.observers.ids", Arrays.asList("fixture"));
        ProviderConfiguration after = new ProviderConfiguration(value::get, Collections.emptyList()); assertEquals(before.cacheNamespace, after.cacheNamespace);
        value.put("integrations.observers.ids", Arrays.asList("fixture", "fixture")); assertThrows(IllegalArgumentException.class, () -> new ObserverSettings(value::get));
        value.put("integrations.observers.ids", Collections.emptyList()); assertThrows(IllegalArgumentException.class, () -> new ObserverSettings(value::get));
        value.put("integrations.observers.ids", Arrays.asList("endpoint://secret")); assertThrows(IllegalArgumentException.class, () -> new ObserverSettings(value::get));
        value.put("integrations.observers.ids", Collections.nCopies(9, "fixture")); assertThrows(IllegalArgumentException.class, () -> new ObserverSettings(value::get));
        for (int i = 0; i < 8; i++) ConnectionGuardApi.registerDecisionObserver("fixture" + i, event -> { });
        assertThrows(IllegalStateException.class, () -> ConnectionGuardApi.registerDecisionObserver("ninth", event -> { }));
    }
    @Test void captureCopiesCachedFactsAndModeAndPublishesOnlyOnce() throws Exception {
        CompletableFuture<DecisionObservation> next = observer("fixture"); DecisionObservers.configure(settings("fixture"));
        DecisionCapture capture = DecisionCapture.begin(Platform.VELOCITY, Phase.LOGIN, "192.0.2.1", UUID.randomUUID(), IdentityTrust.UNTRUSTED);
        VpnResult vpn = new VpnResult("192.0.2.1", true); vpn.setFromCache(true);
        vpn.setVotes(Arrays.asList(new ProviderVote("fixture", ProviderVote.Status.POSITIVE, FailureReason.NONE, 12,
                new DetectionDetails(Collections.singletonMap(DetectionDetails.Type.TOR, true), 15169L, null, null, "JP", 80, null))));
        capture.facts(vpn, new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0), false, true, 1000);
        vpn.setVpn(false); vpn.setVotes(Collections.emptyList());
        Map<String, Object> mode = new HashMap<>(); mode.put("operation.mode", "OBSERVE"); ConnectionGuard.applySettings(GuardSettings.read(mode::get, Collections.emptyList()));
        assertFalse(capture.observe()); capture.flag(Flag.VPN); capture.denied(Reason.VPN_FLAG); capture.close(); capture.close();
        DecisionObservation event = next.get(2, TimeUnit.SECONDS);
        assertEquals(Outcome.DENY, event.getOutcome()); assertEquals(Mode.ENFORCE, event.getMode()); assertEquals(Check.POSITIVE, event.getVpnCheck());
        assertEquals(Check.EXEMPT, event.getGeoCheck()); assertEquals(1, event.getSources().size()); assertTrue(event.getSources().get(0).isFromCache());
        assertEquals(DetectionObservation.Status.POSITIVE, event.getSources().get(0).getObservation().getStatus());
        assertEquals(Boolean.TRUE, event.getSources().get(0).getObservation().getMetadata().getTypes().get(DetectionMetadata.Type.TOR));
        assertFalse(event.getTrustedUuid().isPresent()); assertEquals(1000, event.getObservedAt());
    }
    @Test void metadataConflictAndUnresolvedRuleRemainVisibleWithTheirActualPrecedence() throws Exception {
        CompletableFuture<DecisionObservation> next = observer("fixture"); DecisionObservers.configure(settings("fixture"));
        AccessRule rule = new AccessRule("asn-deny", AccessRule.Effect.DENY, AccessRule.Scope.VPN, "ASN:15169", 0, "synthetic");
        List<ProviderVote> votes = Arrays.asList(new ProviderVote("a", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 0,
                new DetectionDetails(null, 15169L, null, null, null, null, null)), new ProviderVote("b", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 0,
                new DetectionDetails(null, 1234L, null, null, null, null, null)));
        EvidencePolicy.Decision policy = EvidencePolicy.evaluate(Arrays.asList(rule), "192.0.2.1", null, false, AccessRule.Scope.VPN, votes, 1000);
        EvidencePolicy.Decision empty = EvidencePolicy.evaluate(Collections.emptyList(), "192.0.2.1", null, false, AccessRule.Scope.GEO, votes, 1000);
        DecisionCapture capture = DecisionCapture.begin(Platform.BUKKIT, Phase.LOGIN, "192.0.2.1", null, IdentityTrust.UNTRUSTED);
        capture.policy(policy, empty); capture.denied(Reason.ACCESS_RULE); capture.close();
        Rule selected = next.get(2, TimeUnit.SECONDS).getRules().stream().filter(Rule::isSelected).findFirst().get();
        assertEquals(Match.CONFLICT, selected.getMatch()); assertEquals(Effect.DENY, selected.getEffect());
    }
    @Test void unknownMetadataIsReportedAsUnknownAllowedRatherThanACompletedRuleCheck() throws Exception {
        CompletableFuture<DecisionObservation> next = observer("fixture"); DecisionObservers.configure(settings("fixture"));
        AccessRule rule = new AccessRule("asn-deny", AccessRule.Effect.DENY, AccessRule.Scope.VPN, "ASN:15169", 0, "synthetic");
        EvidencePolicy.Decision unresolved = EvidencePolicy.evaluate(Arrays.asList(rule), "192.0.2.1", null, false,
                AccessRule.Scope.VPN, Collections.emptyList(), 1000);
        DecisionCapture capture = DecisionCapture.begin(Platform.BUKKIT, Phase.LOGIN, "192.0.2.1", null, IdentityTrust.UNTRUSTED);
        capture.policy(unresolved, unresolved); capture.close();
        DecisionObservation event = next.get(2, TimeUnit.SECONDS);
        assertEquals(Outcome.ALLOW, event.getOutcome()); assertEquals(Reason.UNKNOWN_ALLOWED, event.getReason());
        assertTrue(event.getRules().stream().allMatch(entry -> entry.getMatch() == Match.UNKNOWN && !entry.isSelected()));
    }
    @Test void overloadUsesTheCapturedObservationModeEvenIfGlobalModeChanges() throws Exception {
        Map<String, Object> value = new HashMap<>(); value.put("operation.mode", "OBSERVE");
        ConnectionGuard.applySettings(GuardSettings.read(value::get, Collections.emptyList()));
        CompletableFuture<DecisionObservation> next = observer("fixture"); DecisionObservers.configure(settings("fixture"));
        DecisionCapture capture = DecisionCapture.begin(Platform.VELOCITY, Phase.LOGIN, "192.0.2.1", null, IdentityTrust.UNTRUSTED);
        value.put("operation.mode", "ENFORCE"); value.put("overload.enabled", true); value.put("overload.deny-connections", true);
        value.put("overload.per-ip-attempts", 1); ConnectionGuard.applySettings(GuardSettings.read(value::get, Collections.emptyList()));
        ConnectionGuard.admitLogin("192.0.2.1", false, false, capture.observe());
        com.github.gerolndnr.connectionguard.core.admission.LoginAdmission refused = ConnectionGuard.admitLogin("192.0.2.1", false, false, capture.observe());
        assertFalse(refused.isAllowed()); assertFalse(refused.shouldDeny()); capture.overload(); capture.close();
        DecisionObservation event = next.get(2, TimeUnit.SECONDS); assertEquals(Mode.OBSERVE, event.getMode());
        assertEquals(Outcome.ALLOW, event.getOutcome()); assertEquals(Reason.OVERLOAD, event.getReason());
        assertEquals(Check.NOT_CHECKED, event.getVpnCheck()); assertTrue(event.getSources().isEmpty());
    }
    @Test void shutdownCannotCreateReplacementWorkersAroundCallbacksIgnoringInterruption() throws Exception {
        CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
        ConnectionGuardApi.registerDecisionObserver("slow", event -> {
            running.countDown();
            for (;;) { try { release.await(); return; } catch (InterruptedException ignored) { } }
        });
        DecisionObservers.configure(settings("slow")); DecisionObservers.publish(sample()); DecisionObservers.publish(sample());
        try {
            assertTrue(running.await(2, TimeUnit.SECONDS)); DecisionObservers.shutdown();
            CompletableFuture<DecisionObservation> next = observer("next");
            assertThrows(IllegalStateException.class, () -> DecisionObservers.configure(settings("next")));
            release.countDown(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); boolean activated = false;
            while (!activated && System.nanoTime() < deadline) {
                try { DecisionObservers.configure(settings("next")); activated = true; }
                catch (IllegalStateException terminating) { Thread.sleep(5); }
            }
            assertTrue(activated); DecisionObservers.publish(sample()); next.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
}
