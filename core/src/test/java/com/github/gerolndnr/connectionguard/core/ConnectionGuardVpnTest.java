package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ConnectionGuardVpnTest {
    private static final String IP = "192.0.2.1";
    private RecordingCache cache;

    @BeforeEach
    void configure() {
        cache = new RecordingCache();
        ConnectionGuard.setCacheProvider(cache);
        ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>());
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        ConnectionGuard.setLogger(logger);
    }

    @Test
    void retriesAfterEveryProviderWasUnavailable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        providers(ip -> calls.incrementAndGet() == 1
                ? CompletableFuture.completedFuture(Optional.empty()) : verdict(true));

        assertFalse(query().isVpn());
        assertEquals(0, cache.writes);
        assertTrue(query().isVpn(), "A recovered provider must be queried for the same IP.");
        assertEquals(2, calls.get());
    }

    @Test
    void retriesAnIncompleteNegativeVerdict() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        providers(ip -> verdict(false), ip -> calls.incrementAndGet() == 1
                ? CompletableFuture.completedFuture(Optional.empty()) : verdict(true));

        assertFalse(query().isVpn());
        assertEquals(0, cache.writes);
        assertTrue(query().isVpn());
        assertEquals(2, calls.get());
    }

    @Test
    void cachesACompleteNegativeVerdict() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        providers(ip -> { calls.incrementAndGet(); return verdict(false); }, ip -> verdict(false));

        assertFalse(query().isVpn());
        assertFalse(query().isVpn());
        assertEquals(1, cache.writes);
        assertEquals(1, calls.get(), "A complete cached result avoids another provider request.");
    }

    @Test
    void cachesAThresholdMetDespiteAnotherUnavailableProvider() throws Exception {
        providers(ip -> verdict(true), ip -> CompletableFuture.completedFuture(Optional.empty()));

        assertTrue(query().isVpn());
        assertEquals(1, cache.writes);
        assertTrue(cache.result.get().isVpn());
    }

    @Test
    void requiresTheConfiguredPositiveThreshold() throws Exception {
        ConnectionGuard.setRequiredPositiveFlags(2);
        providers(ip -> verdict(true), ip -> verdict(false));

        assertFalse(query().isVpn());
        assertEquals(1, cache.writes, "Every configured provider answered successfully.");
    }

    @Test
    void doesNotCacheAnUnmetThresholdWithAMissingVote() throws Exception {
        ConnectionGuard.setRequiredPositiveFlags(2);
        providers(ip -> verdict(true), ip -> CompletableFuture.completedFuture(Optional.empty()));

        assertFalse(query().isVpn());
        assertEquals(0, cache.writes);
    }

    @Test
    void countsVotesFromHealthyProvidersAfterAnAsynchronousFailure() throws Exception {
        providers(ip -> failed(new IllegalStateException("malformed provider response")), ip -> verdict(true));

        assertTrue(query().isVpn());
        assertEquals(1, cache.writes);
    }

    @Test
    void continuesAfterASynchronousProviderFailure() throws Exception {
        providers(ip -> { throw new IllegalArgumentException("invalid provider configuration"); },
                ip -> verdict(true));

        assertTrue(query().isVpn());
        assertEquals(1, cache.writes);
    }

    @Test
    void continuesAfterACancelledProviderFuture() throws Exception {
        CompletableFuture<Optional<VpnResult>> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        providers(ip -> cancelled, ip -> verdict(true));

        assertTrue(query().isVpn());
        assertEquals(1, cache.writes);
    }

    @Test
    void retriesAfterAnExceptionalProviderRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        providers(ip -> calls.incrementAndGet() == 1
                ? failed(new IllegalStateException("provider unavailable")) : verdict(true));

        assertFalse(query().isVpn());
        assertEquals(0, cache.writes);
        assertTrue(query().isVpn());
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotCacheWhenNoProviderIsConfigured() throws Exception {
        assertFalse(query().isVpn());
        assertEquals(0, cache.writes);
    }

    @Test
    void reusesAnExistingCachedVerdict() throws Exception {
        VpnResult cached = new VpnResult(IP, true);
        cache.result = Optional.of(cached);
        providers(ip -> { throw new AssertionError("Providers must not run on a cache hit."); });

        assertSame(cached, query());
        assertEquals(0, cache.writes);
    }

    @Test
    void preservesProviderMetadata() throws Exception {
        providers(ip -> CompletableFuture.completedFuture(
                Optional.of(new VpnResult(ip, true, Optional.of("Example VPN")))));

        VpnResult result = query();
        assertTrue(result.isVpn());
        assertEquals(IP, result.getIpAddress());
        assertEquals(Optional.of("Example VPN"), result.getVpnProviderName());
    }

    @Test
    void waitsForDelayedProviderResponsesAndSurvivesTheirFailure() throws Exception {
        CountDownLatch requested = new CountDownLatch(2);
        CompletableFuture<Optional<VpnResult>> positive = new CompletableFuture<>();
        CompletableFuture<Optional<VpnResult>> unavailable = new CompletableFuture<>();
        providers(ip -> { requested.countDown(); return positive; },
                ip -> { requested.countDown(); return unavailable; });

        CompletableFuture<VpnResult> query = ConnectionGuard.getVpnResult(IP);
        assertTrue(requested.await(2, TimeUnit.SECONDS));
        assertFalse(query.isDone());
        positive.complete(Optional.of(new VpnResult(IP, true)));
        unavailable.completeExceptionally(new IllegalStateException("delayed failure"));

        assertTrue(query.get(2, TimeUnit.SECONDS).isVpn());
        assertEquals(1, cache.writes);
    }

    @Test
    void aggregationWarningsExcludeExceptionDetails() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        ConnectionGuard.setLogger(logger);
        providers(ip -> failed(new IllegalStateException(
                "https://example.invalid/" + IP + "?key=fake-sensitive-key")));

        assertFalse(query().isVpn());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unavailable"));
        assertFalse(warnings.get(0).contains(IP));
        assertFalse(warnings.get(0).contains("fake-sensitive-key"));
        assertFalse(warnings.get(0).contains("https://"));
    }

    private void providers(VpnProvider... providers) {
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(providers)));
    }

    private VpnResult query() throws Exception {
        return ConnectionGuard.getVpnResult(IP).get(3, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Optional<VpnResult>> verdict(boolean vpn) {
        return CompletableFuture.completedFuture(Optional.of(new VpnResult(IP, vpn)));
    }

    private static CompletableFuture<Optional<VpnResult>> failed(RuntimeException failure) {
        CompletableFuture<Optional<VpnResult>> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static class RecordingCache extends NoCacheProvider {
        private volatile Optional<VpnResult> result = Optional.empty();
        private volatile int writes;

        @Override
        public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<Void> addVpnResult(VpnResult vpnResult) {
            writes++;
            result = Optional.of(vpnResult);
            return CompletableFuture.completedFuture(null);
        }
    }
}
