package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** A local positive-only safety layer. Neither a miss nor an outage is a clean verdict. */
public final class TorExitList implements AutoCloseable {
    public static final String SOURCE = "https://check.torproject.org/torbulkexitlist";
    private final Path directory;
    private final Logger logger;
    private volatile Snapshot snapshot;
    private ScheduledExecutorService updater;
    private volatile boolean closed;
    private volatile boolean lastRefreshFailed;
    public TorExitList(Path directory, Logger logger) {
        this.directory = directory; this.logger = logger;
        try (InputStream bundled = TorExitList.class.getResourceAsStream("/tor-exits.txt")) {
            snapshot = parse(read(bundled));
        } catch (Exception invalid) { throw new IllegalStateException("Bundled Tor list is invalid."); }
        if (directory != null && Files.isRegularFile(directory.resolve("tor-exits.txt"))) {
            try (InputStream disk = Files.newInputStream(directory.resolve("tor-exits.txt"))) { snapshot = parse(read(disk)); }
            catch (Exception invalid) { warn("Saved Tor list invalid; retaining bundled offline protection."); }
        }
    }
    static final class Snapshot {
        final Set<String> addresses; final String hash; final long fetchedAt;
        Snapshot(Set<String> addresses, String hash, long fetchedAt) { this.addresses = Collections.unmodifiableSet(addresses); this.hash = hash; this.fetchedAt = fetchedAt; }
    }
    static Snapshot parse(byte[] bytes) throws Exception {
        if (bytes.length > 524288) throw new IOException("Oversized Tor list.");
        Set<String> addresses = new HashSet<>(); long fetched = 0;
        for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.trim();
            if (line.startsWith("# fetched-at=")) { fetched = Long.parseLong(line.substring(13)); continue; }
            if (line.isEmpty() || line.startsWith("#")) continue;
            addresses.add(Exemptions.normalize(line));
            if (addresses.size() > 20000) throw new IOException("Oversized Tor list.");
        }
        if (addresses.size() < 100 || fetched <= 0 || fetched > System.currentTimeMillis() + 60000) throw new IOException("Incomplete Tor list.");
        StringBuilder hash = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", part & 255));
        return new Snapshot(addresses, hash.toString(), fetched);
    }
    static byte[] read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing Tor list.");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > 524288) throw new IOException("Oversized Tor list.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    public Optional<VpnResult> lookup(String ip) {
        Snapshot current = snapshot;
        if (!current.addresses.contains(Exemptions.normalize(ip))) return Optional.empty();
        VpnResult result = new VpnResult(ip, true); result.setSourceVersion(current.hash);
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class); types.put(DetectionDetails.Type.TOR, true);
        DetectionDetails details = new DetectionDetails(types, null, null, null, null, null, null);
        result.setDetails(details);
        result.setVotes(Collections.singletonList(new ProviderVote("TorExitList", ProviderVote.Status.POSITIVE, FailureReason.NONE, 0, details, 0, current.hash, true)));
        return Optional.of(result);
    }
    public synchronized void start() {
        if (closed || updater != null || directory == null || "false".equalsIgnoreCase(System.getenv("CONNECTIONGUARD_TOR_REFRESH"))) return;
        updater = Executors.newSingleThreadScheduledExecutor(task -> { Thread t = new Thread(task, "cg-tor-refresh"); t.setDaemon(true); return t; });
        updater.scheduleWithFixedDelay(this::refresh, 0, 5, TimeUnit.MINUTES);
    }
    private void refresh() {
        if (closed || !lastRefreshFailed && System.currentTimeMillis() - snapshot.fetchedAt < TimeUnit.HOURS.toMillis(4)) return;
        try {
            OkHttpClient client = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
            byte[] bytes;
            try (Response response = client.newCall(new Request.Builder().url(SOURCE).header("User-Agent", "ConnectionGuard (+https://connectionguard.net)").build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Tor refresh unavailable.");
                byte[] raw = read(response.body().byteStream());
                ByteArrayOutputStream prefixed = new ByteArrayOutputStream();
                prefixed.write(("# fetched-at=" + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8)); prefixed.write(raw); bytes = prefixed.toByteArray();
            }
            Snapshot next = parse(bytes);
            if (closed) return;
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "tor-exits-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try { Files.move(temporary, directory.resolve("tor-exits.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, directory.resolve("tor-exits.txt"), StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
            snapshot = next; lastRefreshFailed = false;
        } catch (Exception unavailable) { lastRefreshFailed = true; warn("Tor list refresh failed; retaining last verified local snapshot. No login-time request is made."); }
    }
    public String describe() {
        return "Tor local exits=" + snapshot.addresses.size() + " ageHours=" + Math.max(0, TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - snapshot.fetchedAt))
                + " stale=" + (System.currentTimeMillis() - snapshot.fetchedAt > TimeUnit.HOURS.toMillis(24)) + " refreshFailed=" + lastRefreshFailed
                + "; snapshot membership blocks offline; absence is not clean evidence";
    }
    private void warn(String message) { if (logger != null) logger.warning(message); }
    @Override public synchronized void close() { closed = true; if (updater != null) updater.shutdownNow(); }
}
