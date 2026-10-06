package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Best-effort exception metadata. Producers only offer to a bounded nonblocking queue.
 * Stack inspection, hashing and deduplication run separately from lookup/login workers.
 * Throwable messages, file names, console lines and suppressed exceptions are never read. */
public final class PluginErrorReports implements AutoCloseable {
    public enum Context { STARTUP, RELOAD, LOOKUP, CACHE, SYNC, COMMAND, OTHER }
    public static final int MAX_BUFFERED = 50, MAX_PER_SYNC = 10;
    private static final String PREFIX = "com.github.gerolndnr.connectionguard.";
    private static final Pattern CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,30}");
    private static final Pattern METHOD = Pattern.compile("[A-Za-z_$<][A-Za-z0-9_$<>]{0,127}");
    private static volatile PluginErrorReports current;
    private final ConcurrentLinkedQueue<Pending> incoming = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    // One slot per raw exception or sanitized fingerprint, including filtering in progress.
    private final AtomicInteger retained = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final LinkedHashMap<String, Report> reports = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Thread worker;
    private volatile boolean enabled, closed;
    private volatile long generation;

    PluginErrorReports(LongSupplier clock, boolean background) {
        this.clock = clock;
        worker = background ? new Thread(this::work, "ConnectionGuard-error-reports") : null;
        if (worker != null) { worker.setDaemon(true); worker.start(); }
    }
    public static synchronized void configure(boolean enabled) {
        if (current == null && enabled) current = new PluginErrorReports(System::currentTimeMillis, true);
        if (current != null) current.setEnabled(enabled);
    }
    public static void record(Throwable failure, Context context) {
        PluginErrorReports owner = current;
        if (owner != null) owner.offer(failure, context);
    }
    static JsonArray drainForSync() {
        PluginErrorReports owner = current;
        return owner == null ? new JsonArray() : owner.drain();
    }
    public static synchronized void shutdown() {
        PluginErrorReports owner = current; current = null;
        if (owner != null) owner.close();
    }
    public static String describe() {
        PluginErrorReports owner = current;
        if (owner == null) return "Cloud error reports: off buffered=0 queued=0 dropped=0";
        synchronized (owner.reports) {
            return "Cloud error reports: " + (owner.enabled ? "on" : "off") + " buffered=" + owner.reports.size()
                    + " queued=" + Math.max(0, owner.queued.get()) + " dropped=" + owner.dropped.get();
        }
    }
    void setEnabled(boolean next) {
        if (enabled == next || closed) return;
        enabled = next;
        if (!next) {
            generation++;
            clearIncoming();
            synchronized (reports) { retained.addAndGet(-reports.size()); reports.clear(); }
        }
        if (worker != null) LockSupport.unpark(worker);
    }
    void offer(Throwable failure, Context context) {
        if (!enabled || closed || failure == null) return;
        long version = generation;
        if (retained.getAndIncrement() >= MAX_BUFFERED) { retained.decrementAndGet(); dropped.incrementAndGet(); return; }
        if (!enabled || closed || version != generation) { retained.decrementAndGet(); return; }
        queued.incrementAndGet();
        incoming.offer(new Pending(failure, context == null ? Context.OTHER : context, clock.getAsLong(), version));
        // A producer racing shutdown must not leave a Throwable behind after the worker exits.
        if (!enabled || closed || version != generation) clearIncoming();
        if (worker != null) LockSupport.unpark(worker);
    }
    private void work() {
        try {
            while (!closed) {
                processPending();
                LockSupport.parkNanos(this, 100_000_000L);
            }
        } finally { clearIncoming(); }
    }
    /** Also used by the Cloud worker before a batch, never by an admission thread. */
    void processPending() {
        for (int i = 0; i < MAX_BUFFERED; i++) {
            Pending pending = incoming.poll();
            if (pending == null) return;
            queued.decrementAndGet();
            boolean transferred = false;
            try {
                if (!enabled || closed || pending.generation != generation) continue;
                final Report report;
                try { report = sanitize(pending); }
                catch (RuntimeException | LinkageError invalid) { dropped.incrementAndGet(); continue; }
                if (report == null) continue;
                synchronized (reports) {
                    if (!enabled || closed || pending.generation != generation) continue;
                    Report existing = reports.get(report.fingerprint);
                    if (existing != null) {
                        existing.count = Math.min(1_000_000_000L, existing.count + 1);
                        existing.first = Math.min(existing.first, report.first); existing.last = Math.max(existing.last, report.last);
                    } else { reports.put(report.fingerprint, report); transferred = true; }
                }
            } finally { if (!transferred) retained.decrementAndGet(); }
        }
    }
    JsonArray drain() {
        processPending();
        JsonArray batch = new JsonArray();
        synchronized (reports) {
            if (!enabled || closed) return batch;
            Iterator<Report> iterator = reports.values().iterator();
            while (iterator.hasNext() && batch.size() < MAX_PER_SYNC) {
                batch.add(iterator.next().json()); iterator.remove(); retained.decrementAndGet();
            }
        }
        return batch;
    }
    long dropped() { return dropped.get(); }
    int buffered() { synchronized (reports) { return reports.size(); } }
    private void clearIncoming() { while (incoming.poll() != null) { queued.decrementAndGet(); retained.decrementAndGet(); } }
    @Override public void close() { setEnabled(false); closed = true; if (worker != null) LockSupport.unpark(worker); }

    private static boolean validClass(String name) { return name.length() <= 200 && CLASS.matcher(name).matches(); }
    private static Report sanitize(Pending pending) {
        Throwable failure = pending.failure;
        String type = failure.getClass().getName(), causeType = null;
        if (!validClass(type)) return null;
        List<Frame> frames = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; failure != null; depth++) {
            // Cycles/unbounded causes cannot truthfully supply a deepest cause; discard them.
            if (depth >= 32 || !seen.add(failure)) return null;
            if (depth > 0) causeType = failure.getClass().getName();
            if (frames.size() < 12) {
                StackTraceElement[] trace = failure.getStackTrace();
                for (int i = 0; i < trace.length && i < 256 && frames.size() < 12; i++) {
                    StackTraceElement frame = trace[i];
                    String name = frame.getClassName(), method = frame.getMethodName();
                    if (!name.startsWith(PREFIX) || !validClass(name) || !METHOD.matcher(method).matches()) continue;
                    int line = frame.getLineNumber();
                    frames.add(new Frame(name, method, line >= 0 && line <= 1_000_000 ? line : null));
                }
            }
            failure = failure.getCause();
        }
        if (frames.isEmpty() || causeType != null && !validClass(causeType)) return null;
        StringBuilder canonical = new StringBuilder(type).append('\n');
        for (int i = 0; i < Math.min(5, frames.size()); i++) {
            Frame frame = frames.get(i);
            canonical.append(frame.name).append('#').append(frame.method).append(':')
                    .append(frame.line == null ? "" : frame.line).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder();
            for (int i = 0; i < 8; i++) fingerprint.append(String.format(Locale.ROOT, "%02x", hash[i] & 255));
            return new Report(type, causeType, frames, pending.context, fingerprint.toString(), pending.at);
        } catch (java.security.NoSuchAlgorithmException impossible) { return null; }
    }
    private static final class Pending {
        final Throwable failure; final Context context; final long at, generation;
        Pending(Throwable failure, Context context, long at, long generation) {
            this.failure = failure; this.context = context; this.at = at; this.generation = generation;
        }
    }
    private static final class Frame {
        final String name, method; final Integer line;
        Frame(String name, String method, Integer line) { this.name = name; this.method = method; this.line = line; }
        JsonObject json() {
            JsonObject json = new JsonObject(); json.addProperty("class", name); json.addProperty("method", method);
            if (line == null) json.add("line", JsonNull.INSTANCE); else json.addProperty("line", line);
            return json;
        }
    }
    private static final class Report {
        final String type, causeType, fingerprint; final List<Frame> frames; final Context context;
        long count = 1, first, last;
        Report(String type, String causeType, List<Frame> frames, Context context, String fingerprint, long at) {
            this.type = type; this.causeType = causeType; this.frames = frames; this.context = context;
            this.fingerprint = fingerprint; first = last = at;
        }
        JsonObject json() {
            JsonObject json = new JsonObject(); json.addProperty("type", type);
            if (causeType == null) json.add("cause_type", JsonNull.INSTANCE); else json.addProperty("cause_type", causeType);
            JsonArray own = new JsonArray(); frames.forEach(frame -> own.add(frame.json())); json.add("frames", own);
            json.addProperty("context", context.name()); json.addProperty("fingerprint", fingerprint);
            json.addProperty("count", count); json.addProperty("first_at", first); json.addProperty("last_at", last);
            return json;
        }
    }
}
