package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ProviderResilienceTest {
    @BeforeEach void setup() {
        ConnectionGuard.configureLookup(new LookupSettings(2000, 1000, 16, 512, 512, 1, 30000));
        ConnectionGuard.setCacheProvider(new MemoryCacheProvider()); ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>());
    }
    @AfterEach void reset() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
        ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    private CompletableFuture<Optional<VpnResult>> failure(FailureReason reason) {
        CompletableFuture<Optional<VpnResult>> f = new CompletableFuture<>(); f.completeExceptionally(new LookupException(reason)); return f;
    }
    @Test void validNegativeStopsWithoutSendingToBackupAndIsCached() throws Exception {
        AtomicInteger sent = new AtomicInteger(), backup = new AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(ip -> { sent.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false))); },
                ip -> { backup.incrementAndGet(); return failure(FailureReason.NETWORK); })));
        ConnectionGuard.setFailover(true, 3);
        assertEquals(ProviderVote.Status.NEGATIVE, ConnectionGuard.getVpnResult("192.0.2.7").get().getStatus());
        assertTrue(ConnectionGuard.getVpnResult("192.0.2.7").get().isFromCache());
        assertEquals(1, sent.get()); assertEquals(0, backup.get());
    }
    @Test void fourFailuresAdvanceToFirstValidProviderWithoutConsensus() throws Exception {
        for (FailureReason reason : Arrays.asList(FailureReason.TIMEOUT, FailureReason.RATE_LIMIT, FailureReason.INVALID_RESPONSE, FailureReason.NETWORK)) {
            AtomicInteger backup = new AtomicInteger(), last = new AtomicInteger();
            ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(ip -> failure(reason), ip -> {
                backup.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))); },
                    ip -> { last.incrementAndGet(); return failure(FailureReason.NETWORK); })));
            ConnectionGuard.setFailover(true, 3);
            VpnResult result = ConnectionGuard.getVpnResult("192.0.2." + (20 + reason.ordinal())).get();
            assertTrue(result.isVpn()); assertEquals(2, result.getVotes().size()); assertEquals(reason, result.getVotes().get(0).getReason());
            assertEquals(1, backup.get()); assertEquals(0, last.get());
        }
    }
    @Test void exhaustedBudgetSkipsPrimaryAndDoctorReportsIt() throws Exception {
        AtomicInteger primary = new AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(ip -> { primary.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false))); },
                ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))))));
        ConnectionGuard.setFailover(true, 3);
        ConnectionGuard.setProviderBudget(ConnectionGuard.providerId(ConnectionGuard.getVpnProviders().get(0), 0), 1, 0);
        ConnectionGuard.getVpnResult("192.0.2.30").get();
        assertTrue(ConnectionGuard.getVpnResult("192.0.2.31").get().isVpn()); assertEquals(1, primary.get());
        assertTrue(com.github.gerolndnr.connectionguard.core.commands.OperationsCommands.doctor().stream().anyMatch(line -> line.contains("BUDGET_EXHAUSTED")));
        assertTrue(ConnectionGuard.providerHealth().values().stream().anyMatch(h -> h.snapshot().lastReason == FailureReason.BUDGET_EXHAUSTED));
    }
    @Test void hundredSharedCallersProduceOneAttemptPerVisitedProvider() throws Exception {
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger(); CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<Optional<VpnResult>> pending = new CompletableFuture<>();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(ip -> { first.incrementAndGet(); called.countDown(); return pending; },
                ip -> { second.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))); })));
        ConnectionGuard.setFailover(true, 3); List<CompletableFuture<VpnResult>> clients = new ArrayList<>();
        for (int i = 0; i < 100; i++) clients.add(ConnectionGuard.getVpnResult(i % 2 == 0 ? "2001:db8::12" : "2001:db8:0:0:0:0:0:12"));
        assertTrue(called.await(1, TimeUnit.SECONDS)); pending.completeExceptionally(new LookupException(FailureReason.NETWORK));
        for (CompletableFuture<VpnResult> client : clients) assertTrue(client.get().isVpn());
        assertEquals(1, first.get()); assertEquals(1, second.get());
    }
    @Test void twoHundredDistinctBurstSubjectsReceiveConcreteChecks() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> ProviderHttp.submit(() -> {
            checks.incrementAndGet(); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.of(new VpnResult(ip, true));
        }))));
        ConnectionGuard.setFailover(true, 3); List<CompletableFuture<VpnResult>> clients = new ArrayList<>();
        for (int i = 1; i <= 200; i++) clients.add(ConnectionGuard.getVpnResult("198.51.100." + i));
        int concrete = 0; for (CompletableFuture<VpnResult> client : clients) if (client.get().getStatus() == ProviderVote.Status.POSITIVE) concrete++;
        assertEquals(200, concrete); assertEquals(200, checks.get());
    }
    @Test void strictSingleAttemptDoesNotRetryAnActualQuotaDenial() throws Exception {
        AtomicInteger backup = new AtomicInteger();
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(ip -> failure(FailureReason.BUDGET_EXHAUSTED), ip -> {
            backup.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))); })));
        ConnectionGuard.setFailover(true, 1);
        assertEquals(ProviderVote.Status.UNKNOWN, ConnectionGuard.getVpnResult("192.0.2.210").get().getStatus());
        assertEquals(0, backup.get());
    }

}
