package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class LookupControlTest {
    @BeforeEach void setup() throws Exception {
        retireFixtureRuntime();
        ConnectionGuard.configureLookup(new LookupSettings(200, 100, 2, 4, 8, 3, 100));
        ConnectionGuard.setCacheProvider(new NoCacheProvider());
        ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>());
        Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF); ConnectionGuard.setLogger(logger);
    }
    @AfterEach void reset() throws Exception {
        retireFixtureRuntime();
        ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    private static void retireFixtureRuntime() throws Exception {
        LookupRuntime previous = ConnectionGuard.getLookupRuntime();
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < limit) {
            previous.retireIfIdle();
            // isIdle alone is insufficient after shutdown: an idle executor may
            // still be terminating. The production replacement guard correctly
            // rejects that short window. Do not force-close outstanding work.
            if (!previous.isOpen() && previous.retireIfIdle()) return;
            Thread.sleep(2);
        }
        fail("Test left transport work, deadlines or retiring executors active.");
    }
    void provider(VpnProvider provider) { ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(provider))); }
    @Test void aHundredCallersShareOneProviderRequestAndCancellationIsIsolated() throws Exception {
        ConnectionGuard.configureLookup(new LookupSettings(1000, 100, 2, 4, 8, 3, 100));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<Optional<VpnResult>> pending = new CompletableFuture<>();
        provider(ip -> { calls.incrementAndGet(); called.countDown(); return pending; });
        List<CompletableFuture<VpnResult>> callers = new ArrayList<>();
        for (int i = 0; i < 100; i++) callers.add(ConnectionGuard.getVpnResult("192.0.2.1"));
        callers.get(0).cancel(false);
        assertTrue(called.await(1, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        pending.complete(Optional.of(new VpnResult("192.0.2.1", true)));
        for (int i = 1; i < callers.size(); i++) assertEquals(ProviderVote.Status.POSITIVE, callers.get(i).get().getStatus());
        assertTrue(ConnectionGuard.lookupStats().contains("shared=99"));
    }
    @Test void deadlineRetainsAHealthyVoteWithoutInventingTheMissingVote() throws Exception {
        ConnectionGuard.setRequiredPositiveFlags(2);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(
                ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))),
                ip -> new CompletableFuture<>())));
        VpnResult result = ConnectionGuard.getVpnResult("192.0.2.1").get(1, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.UNKNOWN, result.getStatus());
        assertEquals(ProviderVote.Status.POSITIVE, result.getVotes().get(0).getStatus());
        assertEquals(FailureReason.TIMEOUT, result.getVotes().get(1).getReason());
        assertEquals(2, ConnectionGuard.getRequiredPositiveFlags());
    }
    @Test void stuckCacheCannotHoldALoginPastTheDeadline() throws Exception {
        ConnectionGuard.setCacheProvider(new NoCacheProvider() {
            @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) { return new CompletableFuture<>(); }
        });
        provider(ip -> { throw new AssertionError("Provider must not run during hung cache read."); });
        VpnResult result = ConnectionGuard.getVpnResult("192.0.2.1").get(1, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.UNKNOWN, result.getStatus());
        assertEquals(FailureReason.TIMEOUT, result.getVotes().get(0).getReason());
    }
    @Test void expiredVpnCacheReadCannotRestartAfterALongerRuntimeReload() throws Exception {
        CompletableFuture<Optional<VpnResult>> cache = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger(), writes = new AtomicInteger();
        ConnectionGuard.setCacheProvider(new NoCacheProvider() {
            @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) { return cache; }
            @Override public CompletableFuture<Void> addVpnResult(VpnResult value) {
                writes.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        });
        provider(ip -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false))); });
        assertEquals(ProviderVote.Status.UNKNOWN, ConnectionGuard.getVpnResult("192.0.2.240").get(1, TimeUnit.SECONDS).getStatus());
        awaitIdle();
        ConnectionGuard.configureLookup(new LookupSettings(1000, 100, 2, 4, 8, 3, 100));
        cache.complete(Optional.empty());
        awaitIdle();
        assertEquals(0, calls.get(), "An expired cache read must not start a provider on the replacement runtime.");
        assertEquals(0, writes.get(), "The retired query must not write facts into the replacement cache configuration.");
    }
    @Test void expiredGeoCacheReadCannotRestartAfterALongerRuntimeReload() throws Exception {
        CompletableFuture<Optional<GeoResult>> cache = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger(), writes = new AtomicInteger();
        ConnectionGuard.setCacheProvider(new NoCacheProvider() {
            @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) { return cache; }
            @Override public CompletableFuture<Void> addGeoResult(GeoResult value) {
                writes.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        });
        ConnectionGuard.setGeoProvider(ip -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new GeoResult(ip, "PT", "Test", "Test"))); });
        try {
            assertEquals(FailureReason.TIMEOUT, ConnectionGuard.getGeoLookup("192.0.2.241").get(1, TimeUnit.SECONDS).getReason());
            awaitIdle();
            ConnectionGuard.configureLookup(new LookupSettings(1000, 100, 2, 4, 8, 3, 100));
            cache.complete(Optional.empty());
            awaitIdle();
            assertEquals(0, calls.get(), "An expired geo cache read must not start a provider on the replacement runtime.");
            assertEquals(0, writes.get());
        } finally { ConnectionGuard.setGeoProvider(null); }
    }
    private static void awaitIdle() throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
    }
    @Test void admissionRejectsUniqueIpsBeyondTheBound() throws Exception {
        ConnectionGuard.configureLookup(new LookupSettings(1000, 100, 1, 1, 2, 3, 100));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(2);
        CompletableFuture<Optional<VpnResult>> pending = new CompletableFuture<>();
        provider(ip -> { calls.incrementAndGet(); called.countDown(); return pending.thenApply(ignored -> Optional.of(new VpnResult(ip, false))); });
        CompletableFuture<VpnResult> first = ConnectionGuard.getVpnResult("192.0.2.1");
        CompletableFuture<VpnResult> second = ConnectionGuard.getVpnResult("192.0.2.2");
        try {
            VpnResult rejected = ConnectionGuard.getVpnResult("192.0.2.3").get();
            assertEquals(FailureReason.OVERLOADED, rejected.getVotes().get(0).getReason());
            assertTrue(called.await(1, TimeUnit.SECONDS)); assertEquals(2, calls.get());
        } finally {
            pending.complete(Optional.empty());
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
        }
    }
    @Test void workerPoolAndQueueRejectExcessWorkWithoutGrowing() throws Exception {
        try (LookupRuntime runtime = new LookupRuntime(new LookupSettings(1000, 100, 1, 1, 2, 3, 100))) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            CompletableFuture<Integer> first = runtime.submit(() -> {
                started.countDown();
                try { release.await(1, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return 1;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            CompletableFuture<Integer> second = runtime.submit(() -> 2);
            CompletableFuture<Integer> rejected = runtime.submit(() -> 3);
            assertEquals(FailureReason.OVERLOADED, LookupException.reason(assertThrows(ExecutionException.class, rejected::get)));
            assertEquals(1, runtime.getQueueSize());
            release.countDown(); assertEquals(1, first.get()); assertEquals(2, second.get());
        }
    }
    @Test void quotaPreventsTheFourthDistinctLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        VpnProvider provider = ip -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false))); };
        provider(provider);
        ConnectionGuard.setProviderBudget(ConnectionGuard.providerId(provider, 0), 3, 0);
        for (int i = 1; i <= 3; i++) assertEquals(ProviderVote.Status.NEGATIVE, ConnectionGuard.getVpnResult("192.0.2." + i).get().getStatus());
        VpnResult fourth = ConnectionGuard.getVpnResult("192.0.2.4").get();
        assertEquals(FailureReason.BUDGET_EXHAUSTED, fourth.getVotes().get(0).getReason());
        assertEquals(3, calls.get());
    }
    @Test void aCompletionContinuationStartsAFreshFlightInsteadOfReusingTheFinishedAnswer() throws Exception {
        try (LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults())) {
            LookupCoordinator coordinator = new LookupCoordinator(runtime);
            CompletableFuture<String> pending = new CompletableFuture<>();
            AtomicInteger requests = new AtomicInteger();
            CompletableFuture<String> first = coordinator.query("same-ip", () -> { requests.incrementAndGet(); return pending; }, () -> "timeout", () -> "overloaded");
            CompletableFuture<String> next = first.thenCompose(value -> coordinator.query("same-ip", () -> {
                requests.incrementAndGet(); return CompletableFuture.completedFuture("fresh");
            }, () -> "timeout", () -> "overloaded"));
            pending.complete("old");
            assertEquals("fresh", next.get(1, TimeUnit.SECONDS)); assertEquals(2, requests.get());
            assertEquals(0, coordinator.inflight());
        }
    }
    @Test void rateLimitHealthIsPublishedBeforeTheResultContinuationCanRetry() throws Exception {
        CompletableFuture<Optional<VpnResult>> pending = new CompletableFuture<>();
        CountDownLatch called = new CountDownLatch(1);
        provider(ip -> { called.countDown(); return pending; });
        CompletableFuture<Boolean> observed = ConnectionGuard.getVpnResult("192.0.2.1").thenApply(result ->
                ConnectionGuard.providerHealth().values().stream().anyMatch(state -> state.describe().contains("last=RATE_LIMIT")));
        assertTrue(called.await(1, TimeUnit.SECONDS));
        pending.completeExceptionally(new LookupException(FailureReason.RATE_LIMIT, 50));
        assertTrue(observed.get(1, TimeUnit.SECONDS));
    }
    @Test void aBlockingExtensionCannotHoldTheCallerOrOutliveItsLookupDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ConnectionGuard.configureLookup(new LookupSettings(150, 100, 1, 2, 2, 3, 100));
        provider(ip -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true)));
        });
        try {
            long started = System.nanoTime();
            CompletableFuture<VpnResult> result = ConnectionGuard.getVpnResult("192.0.2.1");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "Provider invocation must return a future without waiting for the blocked extension.");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(ProviderVote.Status.UNKNOWN, result.get(1, TimeUnit.SECONDS).getStatus());
        } finally { release.countDown(); }
    }
    @Test void rateLimitPausesProviderThenRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        provider(ip -> {
            if (calls.incrementAndGet() > 1) return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false)));
            CompletableFuture<Optional<VpnResult>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new LookupException(FailureReason.RATE_LIMIT, 100)); return failed;
        });
        assertEquals(FailureReason.RATE_LIMIT, ConnectionGuard.getVpnResult("192.0.2.1").get().getVotes().get(0).getReason());
        assertEquals(FailureReason.CIRCUIT_OPEN, ConnectionGuard.getVpnResult("192.0.2.2").get().getVotes().get(0).getReason());
        assertEquals(1, calls.get());
        Thread.sleep(150);
        assertEquals(ProviderVote.Status.NEGATIVE, ConnectionGuard.getVpnResult("192.0.2.3").get().getStatus());
        assertEquals(2, calls.get());
    }
    @Test void expiredFlightDoesNotAuthorizeReplacementAroundBlockedSupplier() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        LookupRuntime previous = ConnectionGuard.getLookupRuntime();
        provider(ip -> {
            running.countDown();
            try { while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } } }
            finally { exited.countDown(); }
            return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false)));
        });
        CompletableFuture<VpnResult> lookup = ConnectionGuard.getVpnResult("192.0.2.200");
        try {
            assertTrue(running.await(1, TimeUnit.SECONDS));
            assertEquals(ProviderVote.Status.UNKNOWN, lookup.get(1, TimeUnit.SECONDS).getStatus());
            assertTrue(ConnectionGuard.lookupStats().contains("inflight=0"));
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.configureLookup(LookupSettings.defaults()));
            assertSame(previous, ConnectionGuard.getLookupRuntime());
        } finally { release.countDown(); assertTrue(exited.await(1, TimeUnit.SECONDS)); }
    }
    @Test void canonicalIpv6SharesTheSameInFlightLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Optional<VpnResult>> pending = new CompletableFuture<>();
        provider(ip -> { calls.incrementAndGet(); return pending; });
        CompletableFuture<VpnResult> one = ConnectionGuard.getVpnResult("2001:db8::1");
        CompletableFuture<VpnResult> two = ConnectionGuard.getVpnResult("2001:0db8:0:0:0:0:0:1");
        pending.complete(Optional.of(new VpnResult("2001:db8::1", false)));
        one.get(); two.get(); assertEquals(1, calls.get());
    }
}
