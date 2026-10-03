package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class LoginChecksTest {
    @BeforeEach void setup() {
        ConnectionGuard.configureLookup(new LookupSettings(200, 100, 2, 8, 8, 100, 100));
        ConnectionGuard.setCacheProvider(new NoCacheProvider()); ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> new CompletableFuture<>())));
        ConnectionGuard.setGeoProvider(null);
    }
    @AfterEach void cleanup() throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle()); ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    LoginChecks.Permission known(boolean value) { return LoginChecks.Permission.known(value); }
    CompletableFuture<LoginChecks.Result> check(long started, LoginChecks.Permission vpn, LoginChecks.Permission geo) {
        return LoginChecks.check("192.0.2.211", ConnectionGuard.getLookupRuntime().getSettings(), started, false, vpn, geo);
    }
    @Test void blockingPermissionFactoryCannotHoldTheCallingThreadOrStartLateDetection() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> {
            queries.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true)));
        })));
        long started = System.nanoTime();
        CompletableFuture<LoginChecks.Result> future = check(started, LoginChecks.Permission.lookup(() -> {
            running.countDown();
            while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return CompletableFuture.completedFuture(false);
        }), known(true));
        try {
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 150);
            assertTrue(running.await(1, TimeUnit.SECONDS)); LoginChecks.Result result = future.get(1, TimeUnit.SECONDS);
            assertTrue(result.isExpired()); assertEquals(ProviderVote.Status.UNKNOWN, result.vpn().getStatus());
            assertEquals(FailureReason.TIMEOUT, result.vpn().getVotes().get(0).getReason()); assertEquals(0, queries.get());
        } finally { release.countDown(); }
        cleanup(); assertEquals(0, queries.get()); assertEquals(0, LoginChecks.active());
    }
    @Test void latePermissionGrantCannotChangeTheResultOrStartProviders() throws Exception {
        CompletableFuture<Boolean> permission = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> { queries.incrementAndGet(); return new CompletableFuture<>(); })));
        LoginChecks.Result result = check(System.nanoTime(), LoginChecks.Permission.lookup(() -> permission), known(true)).get(1, TimeUnit.SECONDS);
        assertFalse(result.vpnExempt()); assertTrue(result.geoExempt()); assertTrue(result.isExpired());
        permission.complete(true); assertFalse(result.vpnExempt()); assertEquals(0, queries.get());
    }
    @Test void aTimelyPositiveFactIsPreservedWhenTheOtherScopeTimesOut() throws Exception {
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))))));
        ConnectionGuard.setGeoProvider(ip -> new CompletableFuture<>());
        LoginChecks.Result result = check(System.nanoTime(), known(false), known(false)).get(1, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.POSITIVE, result.vpn().getStatus());
        assertEquals(FailureReason.TIMEOUT, result.geo().getReason()); assertTrue(result.isExpired());
    }
    @Test void shorterCallerDoesNotCancelALaterCallersSharedDetection() throws Exception {
        CompletableFuture<Optional<VpnResult>> source = new CompletableFuture<>();
        CountDownLatch called = new CountDownLatch(1); java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> { calls.incrementAndGet(); called.countDown(); return source; })));
        long now = System.nanoTime();
        CompletableFuture<LoginChecks.Result> earlier = check(now - TimeUnit.MILLISECONDS.toNanos(140), known(false), known(true));
        assertTrue(called.await(1, TimeUnit.SECONDS));
        CompletableFuture<LoginChecks.Result> later = check(System.nanoTime(), known(false), known(true));
        assertEquals(ProviderVote.Status.UNKNOWN, earlier.get(1, TimeUnit.SECONDS).vpn().getStatus());
        source.complete(Optional.of(new VpnResult("192.0.2.211", true)));
        assertEquals(ProviderVote.Status.POSITIVE, later.get(1, TimeUnit.SECONDS).vpn().getStatus());
        assertEquals(1, calls.get()); assertFalse(source.isCancelled());
    }
    @Test void cancelledCallerDoesNotCancelAnotherCallersSharedDetection() throws Exception {
        CompletableFuture<Optional<VpnResult>> source = new CompletableFuture<>(); CountDownLatch called = new CountDownLatch(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> { called.countDown(); return source; })));
        CompletableFuture<LoginChecks.Result> one = check(System.nanoTime(), known(false), known(true));
        assertTrue(called.await(1, TimeUnit.SECONDS));
        CompletableFuture<LoginChecks.Result> two = check(System.nanoTime(), known(false), known(true)); one.cancel(false);
        source.complete(Optional.of(new VpnResult("192.0.2.211", false)));
        assertEquals(ProviderVote.Status.NEGATIVE, two.get(1, TimeUnit.SECONDS).vpn().getStatus()); assertFalse(source.isCancelled());
    }
    @Test void elapsedPrefaceCountsAgainstTheSameBudget() throws Exception {
        LoginChecks.Result result = check(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(250), known(false), known(true)).get(1, TimeUnit.SECONDS);
        assertTrue(result.isExpired()); assertEquals(ProviderVote.Status.UNKNOWN, result.vpn().getStatus());
    }
    @Test void failedPermissionLookupCannotGrantAnExemptionButDetectionStillRuns() throws Exception {
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))))));
        LoginChecks.Result result = check(System.nanoTime(), LoginChecks.Permission.lookup(() -> {
            throw new NoClassDefFoundError("synthetic private dependency");
        }), known(true)).get(1, TimeUnit.SECONDS);
        assertFalse(result.vpnExempt()); assertEquals(ProviderVote.Status.POSITIVE, result.vpn().getStatus());
    }
    @Test void bothKnownExemptionsAvoidPermissionAndProviderWork() throws Exception {
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> { fail("Exempt login ran provider."); return new CompletableFuture<>(); })));
        LoginChecks.Result result = check(System.nanoTime(), known(true), known(true)).get(1, TimeUnit.SECONDS);
        assertTrue(result.vpnExempt()); assertTrue(result.geoExempt()); assertEquals(FailureReason.NONE, result.geo().getReason());
    }
    @Test void finitePendingLoginCapacityRejectsBeforeCreatingMorePermissionWork() throws Exception {
        CompletableFuture<Boolean> pending = new CompletableFuture<>(); List<CompletableFuture<LoginChecks.Result>> accepted = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger permissionCalls = new java.util.concurrent.atomic.AtomicInteger();
        LoginChecks.Permission permission = LoginChecks.Permission.lookup(() -> { permissionCalls.incrementAndGet(); return pending; });
        for (int i = 0; i < 8; i++) accepted.add(check(System.nanoTime(), permission, known(true)));
        try {
            LoginChecks.Result rejected = check(System.nanoTime(), permission, known(true)).get(1, TimeUnit.SECONDS);
            assertEquals(FailureReason.OVERLOADED, rejected.vpn().getVotes().get(0).getReason()); assertEquals(8, LoginChecks.active());
            for (CompletableFuture<LoginChecks.Result> future : accepted) assertTrue(future.get(1, TimeUnit.SECONDS).isExpired());
            assertTrue(permissionCalls.get() <= 8); assertEquals(0, LoginChecks.active());
        } finally { pending.complete(true); }
    }
    @Test void completedCallerReleasesCapacityBeforeABlockingCompletionConsumer() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1), releaseCallback = new CountDownLatch(1);
        CompletableFuture<LoginChecks.Result> login = check(System.nanoTime(), known(false), known(true));
        login.thenAccept(value -> {
            callbackEntered.countDown();
            try { releaseCallback.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(login.isDone());
            assertEquals(0, LoginChecks.active(), "A published completed caller must not retain a login slot behind its consumer.");
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.configureLookup(LookupSettings.defaults()),
                    "Releasing a caller slot must not retire its physically blocked deadline executor.");
        } finally { releaseCallback.countDown(); }
    }
    @Test void cancelledCallerReleasesCapacityBeforeABlockingCancellationConsumer() throws Exception {
        ConnectionGuard.configureLookup(new LookupSettings(1000, 100, 2, 8, 8, 100, 100));
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        CountDownLatch callbackEntered = new CountDownLatch(1), releaseCallback = new CountDownLatch(1);
        CompletableFuture<LoginChecks.Result> login = check(System.nanoTime(), LoginChecks.Permission.lookup(() -> pending), known(true));
        login.whenComplete((value, failure) -> {
            callbackEntered.countDown();
            try { releaseCallback.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        Thread caller = new Thread(() -> login.cancel(false), "test-login-cancellation");
        caller.start();
        try {
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(login.isCancelled()); assertEquals(0, LoginChecks.active());
        } finally { releaseCallback.countDown(); pending.complete(false); caller.join(1000); }
        assertFalse(caller.isAlive());
    }
    @Test void aChangedCapturedLimitSetCannotUseAnUnrelatedRuntime() throws Exception {
        LoginChecks.Result result = LoginChecks.check("192.0.2.211", LookupSettings.defaults(), System.nanoTime(), false,
                known(false), known(true)).get();
        assertTrue(result.isCancelled()); assertEquals(FailureReason.CANCELLED, result.vpn().getVotes().get(0).getReason());
    }
    @Test void partialProviderVotesRemainAttributedAtTheShorterLoginDeadline() throws Exception {
        ConnectionGuard.setRequiredPositiveFlags(2);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(
                ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))),
                ip -> new CompletableFuture<>())));
        CompletableFuture<Boolean> permission = new CompletableFuture<>(); long started = System.nanoTime();
        ConnectionGuard.getLookupRuntime().schedule(() -> permission.complete(false), 120);
        LoginChecks.Result result = check(started, LoginChecks.Permission.lookup(() -> permission), known(true)).get(1, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.UNKNOWN, result.vpn().getStatus());
        assertEquals(2, result.vpn().getVotes().size());
        assertEquals(ProviderVote.Status.POSITIVE, result.vpn().getVotes().get(0).getStatus());
        assertEquals(FailureReason.TIMEOUT, result.vpn().getVotes().get(1).getReason());
    }
    @Test void snapshotsExcludeFactsProducedAfterTheCallersCutoffWithoutNewRequests() throws Exception {
        CompletableFuture<Optional<VpnResult>> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(2); java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(
                ip -> { requests.incrementAndGet(); started.countDown(); return first; },
                ip -> { requests.incrementAndGet(); started.countDown(); return second; })));
        CompletableFuture<VpnResult> shared = ConnectionGuard.getVpnResult("192.0.2.211");
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS)); long before = System.nanoTime();
            first.complete(Optional.of(new VpnResult("192.0.2.211", true)));
            long limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            Optional<VpnResult> latest;
            do { latest = ConnectionGuard.snapshotVpn("192.0.2.211", System.nanoTime()); }
            while (latest.isPresent() && latest.get().getVotes().get(0).getStatus() != ProviderVote.Status.POSITIVE && System.nanoTime() < limit);
            assertTrue(latest.isPresent()); assertEquals(ProviderVote.Status.POSITIVE, latest.get().getVotes().get(0).getStatus());
            VpnResult earlier = ConnectionGuard.snapshotVpn("192.0.2.211", before).get();
            assertEquals(ProviderVote.Status.UNKNOWN, earlier.getVotes().get(0).getStatus());
            assertEquals(FailureReason.TIMEOUT, earlier.getVotes().get(0).getReason()); assertEquals(2, requests.get());
            assertFalse(shared.isDone()); assertFalse(first.isCancelled());
        } finally { second.complete(Optional.of(new VpnResult("192.0.2.211", false))); shared.get(1, TimeUnit.SECONDS); }
    }
    @Test void shutdownWhileDeadlineWaitsForConfigurationMonitorDoesNotDeadlock() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> {
            running.countDown();
            while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true)));
        })));
        CompletableFuture<LoginChecks.Result> login = check(System.nanoTime(), known(false), known(true));
        LookupRuntime runtime = ConnectionGuard.getLookupRuntime();
        try {
            assertTrue(running.await(1, TimeUnit.SECONDS));
            synchronized (ConnectionGuard.class) {
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); boolean waiting;
                do {
                    waiting = Thread.getAllStackTraces().values().stream().anyMatch(stack -> java.util.Arrays.stream(stack)
                            .anyMatch(frame -> frame.getClassName().equals(ConnectionGuard.class.getName()) && frame.getMethodName().equals("snapshotVpn")));
                    if (!waiting) Thread.sleep(2);
                } while (!waiting && System.nanoTime() < limit);
                assertTrue(waiting, "Deadline never reached its configuration monitor.");
                ConnectionGuard.shutdown(); assertTrue(login.get(1, TimeUnit.SECONDS).isCancelled());
            }
        } finally { release.countDown(); }
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!runtime.retireIfIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(runtime.retireIfIdle()); ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    @Test void configuredBudgetIncludesPermissionWaitInsteadOfStartingAgainAfterIt() throws Exception {
        CompletableFuture<Boolean> permission = new CompletableFuture<>();
        long started = System.nanoTime();
        ConnectionGuard.getLookupRuntime().schedule(() -> permission.complete(false), 150);
        VpnResult result = LoginChecks.check("192.0.2.211", ConnectionGuard.getLookupRuntime().getSettings(), started, false,
                LoginChecks.Permission.lookup(() -> permission), LoginChecks.Permission.known(true)).get(2, TimeUnit.SECONDS).vpn();
        assertEquals(ProviderVote.Status.UNKNOWN, result.getStatus());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed < 300, "Configured 200 ms budget was restarted after permission wait: " + elapsed);
    }
}
