package com.github.gerolndnr.connectionguard.core.lookup;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** One bounded transport pool and timer per plugin, with retirement tied to real work. */
public final class LookupRuntime implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timer;
    private final LookupSettings settings;
    private final Set<Job<?>> jobs = new HashSet<>();
    private final Set<Deadline> deadlines = new HashSet<>();
    private boolean closed;
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
    public synchronized <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        Job<T> job = new Job<>(supplier);
        if (closed) { job.result.completeExceptionally(new LookupException(FailureReason.CANCELLED)); return job.result; }
        jobs.add(job);
        try { executor.execute(job); }
        catch (RejectedExecutionException full) {
            jobs.remove(job); job.result.completeExceptionally(new LookupException(FailureReason.OVERLOADED));
        }
        return job.result;
    }
    /** Internal deadline callbacks only. Closing preserves their finite deadlines. */
    public synchronized ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
        if (closed) throw new RejectedExecutionException("Lookup runtime is closed.");
        Deadline deadline = new Deadline(task); deadlines.add(deadline);
        try { deadline.future = timer.schedule(deadline, delayMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException rejected) { deadlines.remove(deadline); throw rejected; }
        return deadline;
    }
    public LookupSettings getSettings() { return settings; }
    public int getQueueSize() { return executor.getQueue().size(); }
    public int getActiveWorkers() { return executor.getActiveCount(); }
    public synchronized boolean isOpen() { return !closed; }
    public synchronized boolean isIdle() {
        return jobs.isEmpty() && deadlines.isEmpty() && executor.getActiveCount() == 0 && timer.getActiveCount() == 0;
    }
    /** Atomically prevent late submissions before retiring; never replace a live old pool. */
    public synchronized boolean retireIfIdle() {
        if (closed) return executor.isTerminated() && timer.isTerminated();
        if (!isIdle()) return false;
        closed = true; executor.shutdown(); timer.shutdown(); return true;
    }
    @Override public void close() {
        List<Job<?>> unfinished;
        synchronized (this) {
            if (closed) return;
            closed = true; unfinished = new ArrayList<>(jobs);
            for (Runnable queued : executor.shutdownNow()) jobs.remove(queued);
            // Delayed permission/lookup deadlines must still settle their owners after shutdown.
            timer.shutdown();
        }
        for (Job<?> job : unfinished) job.result.completeExceptionally(new LookupException(FailureReason.CANCELLED));
    }
    private final class Job<T> implements Runnable {
        private final Supplier<T> supplier;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Job(Supplier<T> supplier) { this.supplier = Objects.requireNonNull(supplier); }
        @Override public void run() {
            try { if (!result.isDone()) result.complete(supplier.get()); }
            catch (RuntimeException | LinkageError | AssertionError failure) { result.completeExceptionally(failure); }
            finally { synchronized (LookupRuntime.this) { jobs.remove(this); } }
        }
    }
    private final class Deadline implements Runnable, ScheduledFuture<Object> {
        private final Runnable task;
        private ScheduledFuture<?> future;
        private boolean running;
        private Deadline(Runnable task) { this.task = Objects.requireNonNull(task); }
        @Override public void run() {
            synchronized (LookupRuntime.this) { if (!deadlines.contains(this)) return; running = true; }
            try { task.run(); }
            finally { synchronized (LookupRuntime.this) { running = false; deadlines.remove(this); } }
        }
        @Override public boolean cancel(boolean interrupt) {
            synchronized (LookupRuntime.this) {
                boolean cancelled = future.cancel(interrupt);
                if (cancelled && !running) deadlines.remove(this);
                return cancelled;
            }
        }
        @Override public long getDelay(TimeUnit unit) { return future.getDelay(unit); }
        @Override public int compareTo(Delayed other) { return future.compareTo(other); }
        @Override public boolean isCancelled() { return future.isCancelled(); }
        @Override public boolean isDone() { return future.isDone(); }
        @Override public Object get() throws InterruptedException, ExecutionException { return future.get(); }
        @Override public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException { return future.get(timeout, unit); }
    }
}
