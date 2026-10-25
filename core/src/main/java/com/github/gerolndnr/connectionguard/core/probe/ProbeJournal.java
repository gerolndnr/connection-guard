package com.github.gerolndnr.connectionguard.core.probe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The experiment's local data: one JSON line per probed login in {@code experiments/connection-probe-<UTC day>.jsonl}.
 * Nothing is sent anywhere. Accounts, networks and skins appear only as keyed hashes (HMAC-SHA256 with a random key that
 * never leaves {@code experiments/key}), so the lines can be compared with each other but not traced back without the
 * key. Files older than {@link #RETENTION_DAYS} days are deleted, and a day stops at {@link #MAX_BYTES_PER_DAY}.
 */
public final class ProbeJournal {
    public static final int RETENTION_DAYS = 14;
    public static final long MAX_BYTES_PER_DAY = 32L << 20;
    private static final int MAX_SEEN_ACCOUNTS = 200_000;
    /** Leaves out unknown (null) fields. */
    private static final Gson GSON = new Gson();

    private final Path dir;
    private final byte[] key;
    private final long since;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > MAX_SEEN_ACCOUNTS; }
    });
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1024), work -> {
        Thread thread = new Thread(work, "ConnectionGuard-probe-journal"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicLong written = new AtomicLong(), dropped = new AtomicLong();
    private final Consumer<String> warn;
    private volatile boolean warned;

    public ProbeJournal(Path dir, Consumer<String> warn) throws IOException {
        this.dir = dir; this.warn = warn;
        Files.createDirectories(dir);
        Path keyFile = dir.resolve("key");
        if (!Files.exists(keyFile)) {
            byte[] fresh = new byte[32]; new SecureRandom().nextBytes(fresh);
            Files.write(keyFile, (System.currentTimeMillis() + "\n" + Base64.getEncoder().encodeToString(fresh)).getBytes(StandardCharsets.US_ASCII));
        }
        String[] stored = new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII).trim().split("\n");
        since = Long.parseLong(stored[0].trim()); key = Base64.getDecoder().decode(stored[1].trim());
        Path seenFile = dir.resolve("seen-accounts");
        if (Files.exists(seenFile)) {
            List<String> lines = Files.readAllLines(seenFile, StandardCharsets.US_ASCII);
            for (String line : lines) if (!line.isEmpty()) seen.add(line);
            // The file only grows while the server runs; on start it is cut back to the accounts still remembered.
            if (lines.size() > seen.size()) Files.write(seenFile, seen, StandardCharsets.US_ASCII);
        }
        purge();
    }

    /** Keyed hash, 16 hex digits: enough to tell values apart, never reversible without the key. */
    public String hash(String kind, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal((kind + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return out.toString();
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    /** True the first time an account logs in while the experiment runs; {@link #daysRunning()} says how much that means. */
    public synchronized boolean firstSeen(String accountHash) {
        if (!seen.add(accountHash)) return false;
        String line = accountHash + "\n";
        writer.execute(() -> append(dir.resolve("seen-accounts"), line));
        return true;
    }
    public long daysRunning() { return Math.max(0, (System.currentTimeMillis() - since) / 86_400_000L); }

    public void write(JsonObject record) {
        String line = GSON.toJson(record) + "\n";
        try {
            writer.execute(() -> {
                Path file = dir.resolve("connection-probe-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
                try { if (Files.exists(file) && Files.size(file) > MAX_BYTES_PER_DAY) { dropped.incrementAndGet(); return; } }
                catch (IOException ignored) { /* append reports it */ }
                if (append(file, line)) written.incrementAndGet(); else dropped.incrementAndGet();
            });
        } catch (RejectedExecutionException stopped) { dropped.incrementAndGet(); }
    }

    private boolean append(Path file, String line) {
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            out.write(line.getBytes(StandardCharsets.UTF_8)); return true;
        } catch (IOException failed) {
            if (!warned) { warned = true; warn.accept("Connection probe journal cannot write to " + dir + " (" + failed.getClass().getSimpleName() + ")."); }
            return false;
        }
    }

    /** Deletes day files past the retention; the key and the seen accounts stay for as long as the experiment runs. */
    public void purge() {
        LocalDate oldest = LocalDate.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "connection-probe-*.jsonl")) {
            for (Path file : files) {
                String day = file.getFileName().toString().replace("connection-probe-", "").replace(".jsonl", "");
                try { if (LocalDate.parse(day).isBefore(oldest)) Files.delete(file); }
                catch (RuntimeException | IOException skip) { /* not ours, or busy: next purge */ }
            }
        } catch (IOException unreadable) { /* next purge */ }
    }

    public Path directory() { return dir; }
    public long written() { return written.get(); }
    public long dropped() { return dropped.get(); }
    /** Finishes the queued lines, waiting at most two seconds. */
    public void close() {
        writer.shutdown();
        try { writer.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
