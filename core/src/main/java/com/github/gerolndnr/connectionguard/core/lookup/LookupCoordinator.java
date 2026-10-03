package com.github.gerolndnr.connectionguard.core.lookup;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.LongFunction;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded admission before allocating a timer or provider jobs. A caller cannot cancel shared work. */
public final class LookupCoordinator {
    private final Map<String, Flight<?>> flights = new HashMap<>();
    private static final class Flight<T> {
        private final CompletableFuture<T> future;
        private final LongFunction<T> snapshot;
        private Flight(CompletableFuture<T> future, LongFunction<T> snapshot) { this.future = future; this.snapshot = snapshot; }
    }
    private final LookupRuntime runtime;
    private final AtomicLong shared = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    public LookupCoordinator(LookupRuntime runtime) { this.runtime = runtime; }
    public <T> CompletableFuture<T> query(String key, Supplier<CompletableFuture<T>> operation,
                                         Supplier<T> timeout, Supplier<T> overloaded) {
        return query(key, operation, timeout, overloaded, ignored -> timeout.get());
    }
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> query(String key, Supplier<CompletableFuture<T>> operation,
                                         Supplier<T> timeout, Supplier<T> overloaded, LongFunction<T> snapshot) {
        CompletableFuture<T> result;
        Flight<T> flight;
        synchronized (flights) {
            Flight<T> existing = (Flight<T>) flights.get(key);
            if (existing != null) { shared.incrementAndGet(); return existing.future.thenApply(value -> value); }
            if (flights.size() >= runtime.getSettings().maxInflight) {
                rejected.incrementAndGet(); return CompletableFuture.completedFuture(overloaded.get());
            }
            result = new CompletableFuture<>();
            flight = new Flight<>(result, snapshot); flights.put(key, flight);
        }
        final CompletableFuture<T> published = result;
        final CompletableFuture<T> target = new CompletableFuture<>();
        ScheduledFuture<?> deadline;
        try { deadline = runtime.schedule(() -> fallback(target, timeout), runtime.getSettings().deadlineMillis); }
        catch (RejectedExecutionException closed) {
            synchronized (flights) { flights.remove(key, flight); }
            published.completeExceptionally(new LookupException(FailureReason.CANCELLED));
            return published.thenApply(value -> value);
        }
        target.whenComplete((value, error) -> {
            deadline.cancel(false);
            synchronized (flights) { flights.remove(key, flight); }
            if (error == null) published.complete(value); else published.completeExceptionally(error);
        });
        try {
            operation.get().whenComplete((value, error) -> {
                if (error == null) target.complete(value);
                else fallback(target, timeout);
            });
        } catch (RuntimeException error) { fallback(target, timeout); }
        // Every caller observes publication only after the flight has been removed.
        return published.thenApply(value -> value);
    }
    private static <T> void fallback(CompletableFuture<T> target, Supplier<T> timeout) {
        try { target.complete(timeout.get()); }
        catch (RuntimeException invalid) { target.completeExceptionally(invalid); }
    }
    /** Pure fallback projection of an existing flight; never publishes or cancels it. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> snapshot(String key, long notAfterNanos) {
        LongFunction<?> projection;
        synchronized (flights) { Flight<?> flight = flights.get(key); projection = flight == null ? null : flight.snapshot; }
        if (projection == null) return Optional.empty();
        try { return Optional.ofNullable((T) projection.apply(notAfterNanos)); }
        catch (RuntimeException | LinkageError | AssertionError invalid) { return Optional.empty(); }
    }
    public int inflight() { synchronized (flights) { return flights.size(); } }
    public String describe() {
        return "inflight=" + inflight() + " shared=" + shared.get() + " rejected=" + rejected.get()
                + " workers=" + runtime.getActiveWorkers() + " queued=" + runtime.getQueueSize();
    }
}
