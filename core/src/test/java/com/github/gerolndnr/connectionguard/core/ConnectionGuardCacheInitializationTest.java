package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ConnectionGuardCacheInitializationTest {
    @Test
    void waitsUntilTheCacheIsReady() throws Exception {
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        CountDownLatch requested = new CountDownLatch(1);
        cache(() -> { requested.countDown(); return ready; });
        CompletableFuture<Void> initialization = CompletableFuture.runAsync(ConnectionGuard::initializeCache);
        try {
            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertFalse(initialization.isDone(), "Listeners must not be registered before cache readiness.");
            ready.complete(true);
            initialization.get(2, TimeUnit.SECONDS);
        } finally {
            ready.complete(false);
        }
    }

    @Test
    void stopsInitializationWhenTheCacheReportsFailure() {
        cache(() -> CompletableFuture.completedFuture(false));
        assertThrows(IllegalStateException.class, ConnectionGuard::initializeCache);
    }

    @Test
    void rejectsAMissingReadinessResult() {
        cache(() -> CompletableFuture.completedFuture(null));
        assertThrows(IllegalStateException.class, ConnectionGuard::initializeCache);
    }

    @Test
    void stopsInitializationAfterAnAsynchronousFailureWithoutExposingItsDetails() {
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("redis://user:fake-sensitive-password@example.invalid"));
        cache(() -> failed);
        IllegalStateException failure = assertThrows(IllegalStateException.class, ConnectionGuard::initializeCache);
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("fake-sensitive-password"));
        assertTrue(failure.getMessage().contains("configured cache"));
    }

    @Test
    void stopsInitializationAfterASynchronousFailure() {
        cache(() -> { throw new IllegalArgumentException("fake-sensitive-password"); });
        IllegalStateException failure = assertThrows(IllegalStateException.class, ConnectionGuard::initializeCache);
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("fake-sensitive-password"));
    }

    @Test
    void stopsInitializationIfCacheSetupIsCancelled() {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        cache(() -> cancelled);
        assertThrows(IllegalStateException.class, ConnectionGuard::initializeCache);
    }

    @Test
    void stopsInitializationWhenTheCacheDoesNotBecomeReadyInTime() {
        cache(CompletableFuture::new);
        assertThrows(IllegalStateException.class,
                () -> ConnectionGuard.initializeCache(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void restoresTheInterruptFlagWhenInitializationIsInterrupted() {
        cache(CompletableFuture::new);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, ConnectionGuard::initializeCache);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private void cache(Supplier<CompletableFuture<Boolean>> setup) {
        ConnectionGuard.setCacheProvider(new NoCacheProvider() {
            @Override public CompletableFuture<Boolean> setup() { return setup.get(); }
        });
    }
}
