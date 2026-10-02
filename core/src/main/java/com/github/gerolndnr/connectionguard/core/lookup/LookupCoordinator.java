package com.github.gerolndnr.connectionguard.core.lookup;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded admission before allocating a timer or provider jobs. A caller cannot cancel shared work. */
public final class LookupCoordinator {
    private final Map<String, CompletableFuture<?>> flights = new HashMap<>();
    private final LookupRuntime runtime;
    private final AtomicLong shared = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    public LookupCoordinator(LookupRuntime runtime) { this.runtime = runtime; }
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> query(String key, Supplier<CompletableFuture<T>> operation,
                                         Supplier<T> timeout, Supplier<T> overloaded) {
        CompletableFuture<T> result;
        synchronized (flights) {
            CompletableFuture<T> existing = (CompletableFuture<T>) flights.get(key);
            if (existing != null) { shared.incrementAndGet(); return existing.thenApply(value -> value); }
            if (flights.size() >= runtime.getSettings().maxInflight) {
                rejected.incrementAndGet(); return CompletableFuture.completedFuture(overloaded.get());
            }
            result = new CompletableFuture<>();
            flights.put(key, result);
        }
        final CompletableFuture<T> published = result;
        final CompletableFuture<T> target = new CompletableFuture<>();
        ScheduledFuture<?> deadline = runtime.schedule(() -> fallback(target, timeout), runtime.getSettings().deadlineMillis);
        target.whenComplete((value, error) -> {
            deadline.cancel(false);
            synchronized (flights) { flights.remove(key, published); }
            if (error == null) published.complete(value); else published.completeExceptionally(error);
        });
        try {
            operation.get().whenComplete((value, error) -> {
                if (error == null) target.complete(value);
                else fallback(target, timeout);
            });
        } catch (RuntimeException error) { fallback(target, timeout); }
        return target.thenApply(value -> value);
    }
    private static <T> void fallback(CompletableFuture<T> target, Supplier<T> timeout) {
        try { target.complete(timeout.get()); }
        catch (RuntimeException invalid) { target.completeExceptionally(invalid); }
    }
    public int inflight() { synchronized (flights) { return flights.size(); } }
    public String describe() {
        return "inflight=" + inflight() + " shared=" + shared.get() + " rejected=" + rejected.get()
                + " workers=" + runtime.getActiveWorkers() + " queued=" + runtime.getQueueSize();
    }
}
