package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Trusted addons receive best-effort observations, outside the login/provider workers. */
public final class DecisionObservers {
    private static final Map<String, Entry> entries = new HashMap<>();
    private static volatile List<Entry> selected = Collections.emptyList();
    private static volatile long generation;
    private static volatile ThreadPoolExecutor workers;
    private static final AtomicLong dropped = new AtomicLong(), failed = new AtomicLong(), delivered = new AtomicLong();
    private DecisionObservers() { }
    public static synchronized ObserverRegistration register(String id, DecisionObserver observer) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}")) throw new IllegalArgumentException("Invalid observer ID (value redacted).");
        Objects.requireNonNull(observer);
        if (entries.containsKey(id)) throw new IllegalArgumentException("Observer ID already registered.");
        if (entries.size() >= 8) throw new IllegalStateException("At most eight observers may register.");
        Entry entry = new Entry(id, observer); entries.put(id, entry); return entry;
    }
    public static synchronized void configure(ObserverSettings settings) {
        validateActivation(settings);
        List<Entry> next = new ArrayList<>();
        for (String id : settings.ids) { Entry entry = entries.get(id); if (entry != null) next.add(entry); }
        if (!next.isEmpty() && (workers == null || workers.isTerminated())) {
            java.util.concurrent.atomic.AtomicInteger serial = new java.util.concurrent.atomic.AtomicInteger();
            workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), work -> {
                Thread thread = new Thread(work, "ConnectionGuard-observer-" + serial.incrementAndGet()); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            workers.allowCoreThreadTimeOut(true);
        }
        generation++; selected = Collections.unmodifiableList(next);
        if (workers != null) workers.getQueue().clear();
    }
    public static boolean isActive() { return !selected.isEmpty(); }
    public static synchronized long captureGeneration() { return selected.isEmpty() ? -1 : generation; }
    public static void publish(DecisionObservation observation) {
        publish(observation, captureGeneration());
    }
    public static void publish(DecisionObservation observation, long expectedGeneration) {
        ThreadPoolExecutor pool;
        List<Entry> receivers;
        long capturedGeneration;
        synchronized (DecisionObservers.class) { pool = workers; receivers = selected; capturedGeneration = generation; }
        if (pool == null || receivers.isEmpty() || expectedGeneration != capturedGeneration) return;
        for (Entry entry : receivers) {
            try {
                pool.execute(() -> {
                    if (!entry.registered || capturedGeneration != generation) return;
                    try { entry.callback.onDecision(observation); delivered.incrementAndGet(); }
                    catch (Exception | LinkageError | AssertionError invalid) { failed.incrementAndGet(); }
                });
            } catch (RejectedExecutionException full) { dropped.incrementAndGet(); }
        }
    }
    public static long dropped() { return dropped.get(); }
    public static long failed() { return failed.get(); }
    static void recordFailure() { failed.incrementAndGet(); }
    public static long delivered() { return delivered.get(); }
    public static synchronized void validateActivation(ObserverSettings settings) {
        if (!settings.ids.isEmpty() && workers != null && workers.isShutdown() && !workers.isTerminated())
            throw new IllegalStateException("Previous observer callbacks have not terminated; activation rejected.");
    }
    public static synchronized void closeAll() {
        generation++; selected = Collections.emptyList();
        for (Entry entry : new ArrayList<>(entries.values())) entry.close();
        if (workers != null) workers.getQueue().clear();
    }
    public static synchronized void shutdown() {
        closeAll();
        if (workers != null) workers.shutdownNow();
        // Retain the executor: config reload cannot create pools around callbacks ignoring interruption.
    }
    public static synchronized String describe() {
        return "observers selected=" + selected.size() + " registered=" + entries.size()
                + " queued=" + (workers == null ? 0 : workers.getQueue().size()) + " delivered=" + delivered()
                + " dropped=" + dropped() + " failed=" + failed();
    }
    private static final class Entry implements ObserverRegistration {
        private final String id;
        private final DecisionObserver callback;
        private volatile boolean registered = true;
        private Entry(String id, DecisionObserver callback) { this.id = id; this.callback = callback; }
        @Override public String getId() { return id; }
        @Override public boolean isRegistered() { return registered; }
        @Override public void close() {
            synchronized (DecisionObservers.class) { registered = false; entries.remove(id, this); }
        }
    }
}
