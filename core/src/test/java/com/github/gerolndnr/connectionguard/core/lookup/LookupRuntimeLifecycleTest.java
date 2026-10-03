package com.github.gerolndnr.connectionguard.core.lookup;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class LookupRuntimeLifecycleTest {
    static void awaitIdle(LookupRuntime runtime) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!runtime.isIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(runtime.isIdle());
    }
    @Test void cancelledDeadlineReleasesRetirementAndLateWorkIsRejected() throws Exception {
        try (LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults())) {
            ScheduledFuture<?> deadline = runtime.schedule(() -> fail("Cancelled deadline ran."), 1000);
            assertFalse(runtime.retireIfIdle()); assertTrue(deadline.cancel(false));
            awaitIdle(runtime); assertTrue(runtime.retireIfIdle());
            assertThrows(RejectedExecutionException.class, () -> runtime.schedule(() -> { }, 1));
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> runtime.submit(() -> "late").get());
            assertEquals(FailureReason.CANCELLED, LookupException.reason(rejected));
        }
    }
    @Test void shutdownPreservesPendingDeadlineInsteadOfStrandingItsOwner() throws Exception {
        LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults());
        CompletableFuture<Boolean> permission = new CompletableFuture<>();
        runtime.schedule(() -> permission.complete(false), 50); runtime.close();
        assertFalse(permission.get(1, TimeUnit.SECONDS));
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!runtime.retireIfIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(runtime.retireIfIdle());
    }
    @Test void cancellingAnExecutingDeadlineDoesNotHideItsCallback() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        try (LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults())) {
            ScheduledFuture<?> deadline = runtime.schedule(() -> {
                running.countDown(); awaitRelease(release);
            }, 0);
            try {
                assertTrue(running.await(1, TimeUnit.SECONDS)); deadline.cancel(false);
                assertFalse(runtime.retireIfIdle());
            } finally { release.countDown(); }
            awaitIdle(runtime); assertTrue(runtime.retireIfIdle());
        }
    }
    @Test void futureCompletionCallbacksStillCountAsTransportWork() throws Exception {
        CountDownLatch supplierRelease = new CountDownLatch(1), callbackRunning = new CountDownLatch(1), callbackRelease = new CountDownLatch(1);
        try (LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults())) {
            CompletableFuture<String> job = runtime.submit(() -> { awaitRelease(supplierRelease); return "value"; });
            CompletableFuture<Void> continuation = job.thenRun(() -> { callbackRunning.countDown(); awaitRelease(callbackRelease); });
            try {
                supplierRelease.countDown(); assertTrue(callbackRunning.await(1, TimeUnit.SECONDS));
                assertFalse(runtime.retireIfIdle());
            } finally { supplierRelease.countDown(); callbackRelease.countDown(); }
            continuation.get(1, TimeUnit.SECONDS); awaitIdle(runtime); assertTrue(runtime.retireIfIdle());
        }
    }
    @Test void shutdownSettlesQueuedJobsWithoutStartingThemAndCannotReplaceBlockedWorkers() throws Exception {
        LookupRuntime runtime = new LookupRuntime(new LookupSettings(1000, 100, 1, 1, 2, 3, 100));
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<String> first = runtime.submit(() -> { running.countDown(); awaitRelease(release); return "late"; });
        try {
            assertTrue(running.await(1, TimeUnit.SECONDS));
            CompletableFuture<String> queued = runtime.submit(() -> { fail("Shutdown started queued supplier."); return "bad"; });
            runtime.close();
            for (CompletableFuture<String> job : java.util.Arrays.asList(first, queued)) {
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> job.get(1, TimeUnit.SECONDS));
                assertEquals(FailureReason.CANCELLED, LookupException.reason(rejected));
            }
            assertFalse(runtime.retireIfIdle());
        } finally { release.countDown(); runtime.close(); }
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!runtime.retireIfIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(runtime.retireIfIdle());
    }
    @Test void addonLinkageFailureCompletesTheJobAndReleasesItsWorker() throws Exception {
        try (LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults())) {
            ExecutionException failed = assertThrows(ExecutionException.class, () -> runtime.submit(() -> {
                throw new NoClassDefFoundError("synthetic addon dependency");
            }).get(1, TimeUnit.SECONDS));
            assertInstanceOf(NoClassDefFoundError.class, failed.getCause()); awaitIdle(runtime);
            assertTrue(runtime.retireIfIdle());
        }
    }
    @Test void queryAfterShutdownCannotLeakASharedFlightOrInvokeTheSupplier() throws Exception {
        LookupRuntime runtime = new LookupRuntime(LookupSettings.defaults());
        LookupCoordinator coordinator = new LookupCoordinator(runtime); runtime.close();
        CompletableFuture<String> rejected = coordinator.query("late-ip", () -> {
            fail("Shutdown query invoked supplier."); return CompletableFuture.completedFuture("bad");
        }, () -> "timeout", () -> "overloaded");
        ExecutionException error = assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS));
        assertEquals(FailureReason.CANCELLED, LookupException.reason(error));
        assertEquals(0, coordinator.inflight());
    }
    static void awaitRelease(CountDownLatch release) {
        while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
    }
}
