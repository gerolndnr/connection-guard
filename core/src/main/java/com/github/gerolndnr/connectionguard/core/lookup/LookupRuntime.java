package com.github.gerolndnr.connectionguard.core.lookup;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** One bounded transport pool and timer per plugin. Futures never occupy a worker while waiting. */
public final class LookupRuntime implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timer;
    private final LookupSettings settings;
    public LookupRuntime(LookupSettings settings) {
        this.settings = settings;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory workers = task -> {
            Thread thread = new Thread(task, "ConnectionGuard-lookup-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(settings.workers, settings.workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity), workers, new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ConnectionGuard-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }
    public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            if (result.isDone()) return;
            try { result.complete(supplier.get()); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        };
        try { executor.execute(task); }
        catch (RejectedExecutionException full) { result.completeExceptionally(new LookupException(FailureReason.OVERLOADED)); }
        return result;
    }
    public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
        return timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }
    public LookupSettings getSettings() { return settings; }
    public int getQueueSize() { return executor.getQueue().size(); }
    public int getActiveWorkers() { return executor.getActiveCount(); }
    @Override public void close() { timer.shutdownNow(); executor.shutdownNow(); }
}
