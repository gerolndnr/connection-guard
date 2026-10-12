package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.core.lookup.GeoLookup;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Explicit bounded local observations; never an observer, Cloud payload or provider request. */
public final class PolicyObservationCapture implements AutoCloseable {
    public static final int MAX_SAMPLES = 32, MAX_BYTES = 262144;
    public static final long MAX_DURATION_MILLIS = TimeUnit.HOURS.toMillis(1), RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final String FILE_NAME = "observations.json";
    public enum State { DISABLED, ACTIVE, EXPIRED, SAMPLE_LIMIT, SIZE_LIMIT, STOPPED }
    private final LongSupplier monotonicClock, wallClock;
    private volatile Path dataDirectory;
    private volatile Session current;
    private View last = View.disabled();
    private ScheduledExecutorService retention;
    private ScheduledFuture<?> cleanup, sessionExpiry, unsavedExpiry;

    public PolicyObservationCapture() { this(System::nanoTime, System::currentTimeMillis); }
    PolicyObservationCapture(LongSupplier monotonicClock, LongSupplier wallClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock); this.wallClock = Objects.requireNonNull(wallClock);
    }
    public synchronized void configure(Path directory) {
        close(); dataDirectory = directory.toAbsolutePath().normalize();
        // Invalid optional archives never stop admission. Status exposes them for operator review.
        try { refreshSaved(); } catch (IOException | RuntimeException invalid) { }
    }
    public synchronized View start(long durationMillis) throws IOException { return start(durationMillis, false); }
    public synchronized View start(long durationMillis, boolean includeIdentity) throws IOException {
        if (durationMillis < 1 || durationMillis > MAX_DURATION_MILLIS) throw new IllegalArgumentException("Invalid capture window.");
        if (current != null) throw new IllegalStateException("Stop or discard the current capture first.");
        if (refreshSaved() != null) throw new IllegalStateException("Review or clear the previous saved capture first.");
        current = new Session(monotonicClock.getAsLong(), wallClock.getAsLong(), durationMillis, includeIdentity);
        Session started = current; ensureRetention();
        sessionExpiry = retention.schedule(() -> { if (current == started) started.view(monotonicClock.getAsLong()); }, durationMillis, TimeUnit.MILLISECONDS);
        unsavedExpiry = retention.schedule(() -> { synchronized (PolicyObservationCapture.this) { if (current == started) discard(); } }, RETENTION_MILLIS, TimeUnit.MILLISECONDS);
        return current.view(monotonicClock.getAsLong());
    }
    /** Disabled path is one volatile read; active path never waits for another recorder or performs disk I/O. */
    public void record(List<AccessRule> rules, String ip, UUID uuid, boolean trusted, boolean vpnExempt,
                       boolean geoExempt, VpnResult vpn, GeoLookup geo, String geoSource, long asOf, boolean failover) {
        Session session = current;
        if (session != null) session.record(rules, ip, uuid, trusted, vpnExempt, geoExempt, vpn, geo, geoSource, asOf, failover, monotonicClock.getAsLong());
    }
    public synchronized View status() throws IOException {
        View view = current == null ? last : current.view(monotonicClock.getAsLong());
        PolicyReplay.Cases saved = refreshSaved();
        return view.saved(saved == null ? 0 : saved.cases.size(), saved != null && !saved.identitiesRedacted);
    }
    public synchronized View stop() throws IOException {
        Session session = current;
        if (session == null) throw new IllegalStateException("No unsaved capture is available.");
        byte[] bytes = session.finish(monotonicClock.getAsLong());
        PolicyReplay.Cases parsed = PolicyReplay.readCases(new ByteArrayInputStream(bytes));
        writeAtomically(bytes);
        last = session.view(monotonicClock.getAsLong()); current = null;
        cancelSessionTimers(); session.cancel();
        scheduleExpiry(parsed.capturedAt);
        return last.saved(parsed.cases.size(), !parsed.identitiesRedacted);
    }
    public synchronized void discard() {
        Session session = current;
        if (session != null) session.cancel();
        current = null; last = View.disabled(); cancelSessionTimers();
    }
    public synchronized boolean clear() throws IOException {
        if (current != null) throw new IllegalStateException("Stop or discard the current capture first.");
        Path saved = savedFile(false);
        if (saved == null) return false;
        Files.delete(saved); last = View.disabled(); cancelCleanup(); return true;
    }
    @Override public synchronized void close() {
        discard(); cancelCleanup();
        if (retention != null) { retention.shutdownNow(); retention = null; }
    }

    private final class Session {
        private final long startedNanos, startedAt, durationNanos;
        private final boolean includeIdentity;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<JsonObject> samples = new ArrayList<>();
        private final AtomicLong skippedBusy = new AtomicLong();
        private volatile State state = State.ACTIVE;
        private long skippedIdentity, skippedUnsupported, sizeBytes;
        Session(long startedNanos, long startedAt, long durationMillis, boolean includeIdentity) {
            if (startedAt < 1) throw new IllegalArgumentException("Invalid capture clock.");
            this.startedNanos = startedNanos; this.startedAt = startedAt;
            this.durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis); this.includeIdentity = includeIdentity;
        }
        void record(List<AccessRule> rules, String ip, UUID uuid, boolean trusted, boolean vpnExempt,
                    boolean geoExempt, VpnResult vpn, GeoLookup geo, String geoSource, long asOf, boolean failover, long nowNanos) {
            if (state != State.ACTIVE) return;
            if (!lock.tryLock()) { skippedBusy.incrementAndGet(); return; }
            try {
                expire(nowNanos); if (state != State.ACTIVE) return;
                if (!includeIdentity && rules.stream().anyMatch(rule -> !rule.isMetadata())) { skippedIdentity++; return; }
                JsonObject next = PolicyReplay.capturedCase("observation_" + (samples.size() + 1), ip, uuid, trusted,
                        vpnExempt, geoExempt, vpn, geo, geoSource, asOf, !includeIdentity, failover);
                // Validate each immutable copy now, including aggregate/source consistency and actual time.
                JsonArray one = new JsonArray(); one.add(next);
                PolicyReplay.readCases(new ByteArrayInputStream(document(one).toString().getBytes(StandardCharsets.UTF_8)));
                long length = next.toString().getBytes(StandardCharsets.UTF_8).length;
                if (sizeBytes + length > MAX_BYTES - 512) { state = State.SIZE_LIMIT; return; }
                samples.add(next); sizeBytes += length;
                if (samples.size() == MAX_SAMPLES) state = State.SAMPLE_LIMIT;
            } catch (IOException | RuntimeException invalid) { skippedUnsupported++; }
            finally { lock.unlock(); }
        }
        private void expire(long nowNanos) { if (state == State.ACTIVE && nowNanos - startedNanos >= durationNanos) state = State.EXPIRED; }
        void cancel() { lock.lock(); try { state = State.STOPPED; samples.clear(); } finally { lock.unlock(); } }
        View view(long nowNanos) {
            lock.lock();
            try { expire(nowNanos); return new View(state, samples.size(), skippedIdentity, skippedUnsupported, skippedBusy.get(), sizeBytes,
                    state == State.ACTIVE ? Math.max(0, TimeUnit.NANOSECONDS.toMillis(durationNanos - (nowNanos - startedNanos))) : 0,
                    includeIdentity, 0, false); }
            finally { lock.unlock(); }
        }
        byte[] finish(long nowNanos) throws IOException {
            lock.lock();
            try {
                expire(nowNanos); if (state == State.ACTIVE) state = State.STOPPED;
                if (samples.isEmpty()) throw new IllegalStateException("No replayable observations were captured; discard the empty session.");
                JsonArray entries = new JsonArray(); samples.forEach(sample -> entries.add(sample.deepCopy()));
                byte[] bytes = document(entries).toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) throw new IOException("Observation archive exceeds its local size limit.");
                return bytes;
            } finally { lock.unlock(); }
        }
        JsonObject document(JsonArray cases) {
            JsonObject root = new JsonObject(); root.addProperty("schema", 1); root.addProperty("kind", "saved_observations");
            root.addProperty("identity_redacted", !includeIdentity); root.addProperty("captured_at", startedAt); root.add("cases", cases); return root;
        }
    }
    public static final class View {
        public final State state;
        public final int samples, savedSamples;
        public final long skippedIdentity, skippedUnsupported, skippedBusy, sizeBytes, remainingMillis;
        public final boolean includeIdentity, saved, savedIncludesIdentity;
        View(State state, int samples, long skippedIdentity, long skippedUnsupported, long skippedBusy, long sizeBytes,
             long remainingMillis, boolean includeIdentity, int savedSamples, boolean savedIncludesIdentity) {
            this.state = state; this.samples = samples; this.skippedIdentity = skippedIdentity; this.skippedUnsupported = skippedUnsupported;
            this.skippedBusy = skippedBusy; this.sizeBytes = sizeBytes; this.remainingMillis = remainingMillis; this.includeIdentity = includeIdentity;
            this.savedSamples = savedSamples; this.saved = savedSamples > 0; this.savedIncludesIdentity = savedIncludesIdentity;
        }
        View saved(int count, boolean identities) { return new View(state, samples, skippedIdentity, skippedUnsupported, skippedBusy,
                sizeBytes, remainingMillis, includeIdentity, count, identities); }
        static View disabled() { return new View(State.DISABLED, 0, 0, 0, 0, 0, 0, false, 0, false); }
    }
    private PolicyReplay.Cases refreshSaved() throws IOException {
        Path file = savedFile(false);
        if (file == null) { cancelCleanup(); return null; }
        PolicyReplay.Cases saved;
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { saved = PolicyReplay.readCases(input); }
        if (!saved.observed) throw new IOException("Observation archive schema rejected.");
        long now = wallClock.getAsLong();
        if (now >= saved.capturedAt && now - saved.capturedAt >= RETENTION_MILLIS) { Files.delete(file); cancelCleanup(); return null; }
        scheduleExpiry(saved.capturedAt); return saved;
    }
    private void scheduleExpiry(long capturedAt) {
        if (cleanup != null && !cleanup.isDone()) return;
        ensureRetention();
        long age = Math.max(0, wallClock.getAsLong() - capturedAt);
        cleanup = retention.schedule(this::cleanupTick, Math.max(1, RETENTION_MILLIS - age), TimeUnit.MILLISECONDS);
    }
    private void ensureRetention() {
        if (retention != null) return;
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, job -> {
            Thread taskThread = new Thread(job, "connectionguard-policy-retention"); taskThread.setDaemon(true); return taskThread;
        });
        worker.setRemoveOnCancelPolicy(true); retention = worker;
    }
    private synchronized void cleanupTick() {
        cleanup = null;
        try { refreshSaved(); }
        catch (IOException | RuntimeException failure) {
            // Retry privately while running; status exposes corrupt/unremovable files for operator review.
            if (retention != null && !retention.isShutdown()) cleanup = retention.schedule(this::cleanupTick, 1, TimeUnit.MINUTES);
        }
    }
    private void cancelCleanup() { if (cleanup != null) { cleanup.cancel(false); cleanup = null; } }
    private void cancelSessionTimers() {
        if (sessionExpiry != null) { sessionExpiry.cancel(false); sessionExpiry = null; }
        if (unsavedExpiry != null) { unsavedExpiry.cancel(false); unsavedExpiry = null; }
    }
    private Path savedFile(boolean createDirectory) throws IOException {
        Path root = dataDirectory;
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("Policy data directory unavailable.");
        Path actualRoot = root.toRealPath(), directory = root.resolve("policy"), target = directory.resolve(FILE_NAME);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!createDirectory) return null;
            try { Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
            catch (UnsupportedOperationException unsupported) { Files.createDirectory(directory); }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().getParent().equals(actualRoot)) throw new IOException("Policy path rejected.");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > MAX_BYTES) throw new IOException("Observation file rejected.");
        return target;
    }
    private void writeAtomically(byte[] bytes) throws IOException {
        if (savedFile(true) != null) throw new IOException("Previous saved observations are preserved.");
        Path directory = dataDirectory.resolve("policy"); cleanupStaging(directory);
        Path temporary;
        try { temporary = Files.createTempFile(directory, ".cg-observations-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException unsupported) { temporary = Files.createTempFile(directory, ".cg-observations-", ".tmp"); }
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer content = ByteBuffer.wrap(bytes); while (content.hasRemaining()) channel.write(content); channel.force(true);
            }
            if (savedFile(false) != null) throw new IOException("Observation target changed; previous data preserved.");
            // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING.
            // Publish the complete private file exclusively; a concurrent writer wins safely.
            Files.createLink(directory.resolve(FILE_NAME), temporary);
        } catch (UnsupportedOperationException unsupported) { throw new IOException("Exclusive atomic observation-file creation unavailable."); }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* A committed publication must not be reported as failed. */ } }
    }
    private static void cleanupStaging(Path directory) throws IOException {
        List<Path> abandoned = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, ".cg-observations-*.tmp")) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().matches("\\.cg-observations-[0-9]+\\.tmp")) continue;
                if (abandoned.size() == 32 || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Observation staging requires review.");
                abandoned.add(entry);
            }
        }
        for (Path entry : abandoned) Files.delete(entry);
    }
}
