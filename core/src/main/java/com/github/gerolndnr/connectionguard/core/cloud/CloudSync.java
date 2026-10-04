package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.cache.RedisCacheProvider;
import com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderHealth;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background link to Connection Guard Cloud. One daemon thread; never touches a login path.
 * Every failure only reschedules: logins, startup and reloads never wait on the cloud.
 */
public final class CloudSync {
    static final int MAX_EVENTS_PER_SYNC = 500;
    private static final long MIN_DELAY = 5, MAX_DELAY = 3600, FIRST_DELAY = 10;
    private static CloudSync current;
    private static CloudSettings lastSettings;
    private static Path dataDirectory;
    private static Function<String, Object> lastConfig;
    private static DecisionObservation.Platform lastPlatform;
    private static String lastPlatformVersion, lastPluginVersion;
    private static Logger lastLog;
    /** The platform's own reload (/cg reload): re-reads config.yml, layers the managed values, validates, swaps. */
    private static volatile Runnable reloadHook;

    /** Platforms register their reload so dashboard settings can be applied with the same validation as /cg reload. */
    public static void setReloadHook(Runnable hook) { reloadHook = hook; }

    private final CloudSettings settings;
    private final Path dir;
    private final DecisionObservation.Platform platform;
    private final String platformVersion, pluginVersion;
    private final Logger log;
    private final CloudClient client;
    private final CloudRecorder recorder = new CloudRecorder();
    private final ScheduledExecutorService scheduler;
    private final long startedAt = System.currentTimeMillis();
    private final Set<String> executedCommands = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 256; }
    });
    private final List<JsonObject> commandResults = new ArrayList<>();

    private volatile CloudCredentials credentials;
    private volatile boolean claimed;
    private volatile String networkName, linkCode, linkUrl, lastError;
    private volatile long lastSyncAt;
    private JsonObject pending;
    private int failures;
    private volatile CloudManagedConfig managed = CloudManagedConfig.EMPTY;
    private JsonObject configResult;

    private CloudSync(CloudSettings settings, Path dir, DecisionObservation.Platform platform, String platformVersion, String pluginVersion, Logger log) {
        this.settings = settings; this.dir = dir; this.platform = platform; this.platformVersion = platformVersion;
        this.pluginVersion = pluginVersion; this.log = log;
        this.client = new CloudClient(settings.endpoint, pluginVersion);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ConnectionGuard-cloud"); thread.setDaemon(true); return thread;
        });
    }

    // ---- lifecycle ------------------------------------------------------------------------

    /** Starts the link unless switched off. Safe to call again on reload; replaces the running link. */
    public static synchronized void start(Path dataDir, Function<String, Object> config, DecisionObservation.Platform platform,
                                          String platformVersion, String pluginVersion, Logger log) {
        stop();
        dataDirectory = dataDir; lastConfig = config; lastPlatform = platform;
        lastPlatformVersion = platformVersion; lastPluginVersion = pluginVersion; lastLog = log;
        CloudSettings settings;
        try { settings = CloudSettings.read(config, Files.exists(disabledMarker(dataDir))); }
        catch (IllegalArgumentException invalid) {
            log.warning("Connection Guard Cloud stays off: " + invalid.getMessage());
            lastSettings = null;
            return;
        }
        lastSettings = settings;
        if (!settings.enabled) return;
        CloudSync sync = new CloudSync(settings, dataDir.resolve("cloud"), platform, platformVersion, pluginVersion, log);
        current = sync;
        sync.begin();
    }

    public static synchronized void stop() {
        CloudSync sync = current;
        current = null;
        if (sync == null) return;
        DecisionObservers.setInternal(null);
        sync.scheduler.shutdownNow();
        sync.client.close();
    }

    private void begin() {
        credentials = CloudCredentials.load(dir.resolve("credentials.json"));
        managed = CloudManagedConfig.load(dir.getParent());
        if (credentials != null && !credentials.endpoint.equals(settings.endpoint.toString())) credentials = null;
        firstRunNotice();
        DecisionObservers.setInternal(recorder);
        schedule(FIRST_DELAY + new Random().nextInt(10));
    }

    private void firstRunNotice() {
        Path marker = dir.resolve("notice-v1");
        if (Files.exists(marker)) return;
        log.info("Connection Guard Cloud is on: until you link this server, it only sends anonymous totals"
                + " (no IPs, no player data) to " + settings.endpoint.getHost() + ".");
        log.info("Turn it off with cloud.enabled: false in config.yml or /cg cloud disable. Logins never wait on the cloud.");
        try { Files.createDirectories(dir); Files.write(marker, new byte[0]); } catch (IOException ignored) { /* shown again next start */ }
    }

    private void schedule(long seconds) {
        try { scheduler.schedule(this::tick, Math.max(MIN_DELAY, Math.min(MAX_DELAY, seconds)), TimeUnit.SECONDS); }
        catch (RejectedExecutionException stopped) { /* shutting down */ }
    }

    // ---- loop -----------------------------------------------------------------------------

    private void tick() {
        long next;
        try {
            next = credentials == null ? install() : sync();
            failures = 0; lastError = null;
        } catch (IOException | RuntimeException failure) {
            failures++;
            lastError = failure instanceof IOException ? "network unavailable" : "unexpected response";
            next = backoff(-1);
            if (failures == 3) log.warning("Connection Guard Cloud is unreachable; retrying in the background. Logins are not affected.");
            if (failure instanceof RuntimeException) log.log(Level.FINE, "Cloud sync failed", failure);
        }
        schedule(next);
    }

    private long backoff(int retryIn) {
        if (retryIn > 0) return retryIn;
        // First failure: retry soon (often just a dropped connection); then back off exponentially.
        if (failures <= 1) return 5 + new Random().nextInt(5);
        long base = Math.min(1800, 30L << Math.min(6, Math.max(0, failures - 2)));
        return base + new Random().nextInt((int) Math.max(1, base / 4));
    }

    private long install() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("platform", platform.name());
        body.addProperty("platform_version", clip(platformVersion, 128));
        body.addProperty("plugin_version", clip(pluginVersion, 32));
        body.addProperty("java_version", clip(System.getProperty("java.version", "unknown"), 32));
        if (!settings.networkToken.isEmpty()) body.addProperty("network_token", settings.networkToken);
        CloudClient.Reply reply = client.post("/v1/installs", body, null);
        if (reply.status == 426) return tooOld();
        if (reply.status == 401) { log.warning("Connection Guard Cloud rejected cloud.network-token; check it in your dashboard."); return 6 * 3600; }
        if (!reply.ok()) { failures++; return backoff(reply.retryIn()); }
        JsonObject r = reply.body;
        CloudCredentials created = new CloudCredentials(r.get("install_id").getAsString(), r.get("secret").getAsString(), settings.endpoint.toString());
        created.save(dir.resolve("credentials.json"));
        credentials = created;
        applyLink(r);
        return r.get("next_sync_in").getAsLong();
    }

    private long tooOld() {
        lastError = "plugin version too old for the cloud";
        log.warning("This Connection Guard version is too old for Connection Guard Cloud. Update the plugin to keep the dashboard current.");
        return 24 * 3600;
    }

    private long sync() throws IOException {
        if (pending == null) pending = buildSync();
        CloudClient.Reply reply = client.post("/v1/sync", pending, credentials.bearer());
        if (reply.status == 401) {
            // The install was removed (e.g. 30 days unlinked). Start over with a fresh anonymous identity.
            CloudCredentials.delete(dir.resolve("credentials.json"));
            credentials = null; claimed = false; recorder.acceptEvents(false); pending = null;
            return MIN_DELAY;
        }
        if (reply.status == 426) return tooOld();
        if (reply.status == 400 || reply.status == 413) { pending = null; lastError = "server rejected a sync"; return backoff(-1); }
        if (!reply.ok()) { failures++; return backoff(reply.retryIn()); }
        if (pending.getAsJsonObject("status").get("config_result").isJsonObject()) configResult = null; // delivered
        pending = null;
        lastSyncAt = System.currentTimeMillis();
        JsonObject r = reply.body;
        applyLink(r);
        recorder.acceptEvents(r.get("accept_events").getAsBoolean());
        JsonElement commands = r.get("commands");
        if (commands != null && commands.isJsonArray()) for (JsonElement c : commands.getAsJsonArray()) execute(c.getAsJsonObject());
        JsonElement config = r.get("config");
        if (config != null && config.isJsonObject() && applyConfig(config.getAsJsonObject())) return MIN_DELAY; // report the result quickly
        return r.get("next_sync_in").getAsLong();
    }

    private void applyLink(JsonObject r) {
        boolean nowClaimed = r.get("claimed").getAsBoolean();
        String code = r.has("link_code") && !r.get("link_code").isJsonNull() ? r.get("link_code").getAsString() : null;
        String url = r.has("link_url") && !r.get("link_url").isJsonNull() ? r.get("link_url").getAsString() : null;
        networkName = r.has("network_name") && !r.get("network_name").isJsonNull() ? r.get("network_name").getAsString() : null;
        if (nowClaimed && !claimed) log.info("Connection Guard Cloud: linked to " + (networkName == null ? "your network" : "\"" + networkName + "\"") + ".");
        if (!nowClaimed && url != null && !url.equals(linkUrl)) {
            log.info("Link this server to your Connection Guard dashboard: " + url);
            log.info("The link is valid for 24 hours. Run /cg cloud link to show it again.");
        }
        claimed = nowClaimed; linkCode = code; linkUrl = url;
    }

    private JsonObject buildSync() {
        // seq: the server ignores a seq it has already counted, so a retried body is never double counted.
        CloudRecorder.Batch batch = recorder.drain(MAX_EVENTS_PER_SYNC, System.currentTimeMillis());
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("seq", System.currentTimeMillis());
        body.addProperty("plugin_version", clip(pluginVersion, 32));
        body.addProperty("platform_version", clip(platformVersion, 128));
        body.add("status", status(batch));
        body.add("counters", batch.counters);
        JsonArray events = new JsonArray();
        batch.events.forEach(events::add);
        body.add("events", events);
        JsonArray results = new JsonArray();
        synchronized (commandResults) { commandResults.forEach(results::add); commandResults.clear(); }
        body.add("command_results", results);
        return body;
    }

    private JsonObject status(CloudRecorder.Batch batch) {
        JsonObject s = new JsonObject();
        boolean observe = ConnectionGuard.getSettings() != null && ConnectionGuard.getSettings().observe;
        s.addProperty("mode", observe ? "OBSERVE" : "ENFORCE");
        s.addProperty("uptime_seconds", (System.currentTimeMillis() - startedAt) / 1000);
        JsonArray providers = new JsonArray();
        for (Map.Entry<String, ProviderHealth> e : new TreeMap<>(ConnectionGuard.providerHealth()).entrySet()) {
            if (providers.size() >= 32) break;
            ProviderHealth.Snapshot h = e.getValue().snapshot();
            JsonObject p = new JsonObject();
            p.addProperty("id", CloudRecorder.sourceId(e.getKey()));
            p.addProperty("scope", e.getKey().toLowerCase(Locale.ROOT).startsWith("geo") ? "GEO" : "VPN");
            p.addProperty("attempts", Math.min(1_000_000_000L, h.attempts));
            p.addProperty("successes", Math.min(1_000_000_000L, h.successes));
            p.addProperty("last_reason", h.lastReason.name());
            p.addProperty("paused", h.paused);
            p.addProperty("daily_used", Math.min(1_000_000_000L, h.dailyUsed));
            p.add("daily_budget", h.dailyBudget > 0 ? new com.google.gson.JsonPrimitive(h.dailyBudget) : com.google.gson.JsonNull.INSTANCE);
            providers.add(p);
        }
        s.add("providers", providers);
        JsonArray warnings = new JsonArray();
        if (observe) warnings.add("mode.observe");
        if (ConnectionGuard.getVpnProviders() == null || ConnectionGuard.getVpnProviders().isEmpty()) warnings.add("provider.none");
        if (ConnectionGuard.getCacheProvider() instanceof NoCacheProvider) warnings.add("cache.none");
        s.add("warnings", warnings);
        if (managed.version > 0) s.addProperty("config_version", managed.version);
        else s.add("config_version", com.google.gson.JsonNull.INSTANCE);
        s.addProperty("cache_type", ConnectionGuard.getCacheProvider() instanceof RedisCacheProvider ? "REDIS"
                : ConnectionGuard.getCacheProvider() instanceof NoCacheProvider || ConnectionGuard.getCacheProvider() == null ? "NONE" : "SQLITE");
        s.addProperty("buffered_events", batch.buffered);
        s.addProperty("dropped_events", batch.dropped);
        // Settings are only described to the dashboard once linked (exemptions can name players).
        CloudManagedConfig m = managed;
        if (claimed && lastConfig != null) s.add("config", CloudManagedConfig.snapshot(lastConfig));
        else s.add("config", com.google.gson.JsonNull.INSTANCE);
        JsonArray managedPaths = new JsonArray();
        if (claimed) m.values.keySet().forEach(managedPaths::add);
        s.add("managed", managedPaths);
        s.add("config_result", configResult == null ? com.google.gson.JsonNull.INSTANCE : configResult);
        return s;
    }

    // ---- dashboard-managed settings ---------------------------------------------------------

    /**
     * Applies a desired config from the dashboard through the platform's normal reload. Invalid
     * values are refused exactly like an invalid config.yml: the running settings stay active and
     * the previous overlay file is restored. Returns true when a result should be reported soon.
     */
    private boolean applyConfig(JsonObject desired) {
        int version = desired.get("version").getAsInt();
        if (version <= managed.version) return false;
        Runnable hook = reloadHook;
        if (hook == null) { configResult = result(version, false, "This server cannot apply dashboard settings."); return true; }
        final CloudManagedConfig next;
        try {
            next = CloudManagedConfig.next(managed, version, desired.has("reset") && desired.get("reset").getAsBoolean(),
                    desired.getAsJsonObject("values"), desired.has("keep_secrets") ? desired.getAsJsonArray("keep_secrets") : null);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException invalid) {
            configResult = result(version, false, invalid instanceof IllegalArgumentException ? invalid.getMessage() : "Malformed settings.");
            log.warning("Connection Guard Cloud: refused dashboard settings v" + version + ": " + configResult.get("message").getAsString());
            return true;
        }
        Path dataDir = dir.getParent();
        byte[] previous;
        try { previous = CloudManagedConfig.write(dataDir, next); }
        catch (IOException failure) { configResult = result(version, false, "Could not write the settings file on the server."); return true; }
        try {
            hook.run();
            managed = next;
            configResult = result(version, true, null);
            log.info("Connection Guard Cloud: applied dashboard settings v" + version + (next.values.isEmpty() ? " (config.yml only)." : "."));
        } catch (IllegalStateException busy) {
            // Lookups in flight: nothing changed; the next sync delivers the same version again.
            rollback(dataDir, previous);
        } catch (RuntimeException invalid) {
            rollback(dataDir, previous);
            configResult = result(version, false, invalid.getMessage() == null ? "The server rejected these settings." : invalid.getMessage());
            log.warning("Connection Guard Cloud: dashboard settings v" + version + " rejected, previous settings kept: " + configResult.get("message").getAsString());
        }
        return true;
    }

    private void rollback(Path dataDir, byte[] previous) {
        try { CloudManagedConfig.restore(dataDir, previous); }
        catch (IOException failure) { log.warning("Connection Guard Cloud: could not restore the previous settings file."); }
    }

    private static JsonObject result(int version, boolean ok, String message) {
        JsonObject r = new JsonObject();
        r.addProperty("version", version);
        r.addProperty("ok", ok);
        r.addProperty("message", message == null ? null : clip(message, 500));
        return r;
    }

    /** Local escape hatch: drop every dashboard value and use config.yml only. Works with the cloud off, too. */
    public static synchronized String resetSettingsLocally() {
        if (dataDirectory == null) throw new IllegalStateException("Not initialised yet.");
        CloudManagedConfig current = CloudManagedConfig.load(dataDirectory);
        if (current.values.isEmpty()) return "No dashboard settings are active; config.yml applies.";
        Runnable hook = reloadHook;
        byte[] previous;
        try { previous = CloudManagedConfig.write(dataDirectory, new CloudManagedConfig(current.version, new java.util.LinkedHashMap<>())); }
        catch (IOException failure) { return "Could not change the settings file; check file permissions."; }
        try { if (hook != null) hook.run(); }
        catch (RuntimeException rejected) {
            try { CloudManagedConfig.restore(dataDirectory, previous); } catch (IOException ignored) { /* reported below */ }
            return "Reload refused (" + rejected.getMessage() + "); dashboard settings kept. Try again in a moment.";
        }
        if (current() != null) current().managed = CloudManagedConfig.load(dataDirectory);
        return "Dashboard settings removed; config.yml applies again. The dashboard shows this after the next sync.";
    }

    public static synchronized List<String> describeSettings() {
        List<String> lines = new ArrayList<>();
        if (dataDirectory == null) return lines;
        CloudManagedConfig current = CloudManagedConfig.load(dataDirectory);
        if (current.values.isEmpty()) { lines.add("All settings come from config.yml."); return lines; }
        lines.add("Dashboard settings v" + current.version + " override these config.yml values:");
        for (String path : current.values.keySet()) lines.add("  " + path + (CloudManagedConfig.isSecret(path) ? " (secret)" : " = " + current.values.get(path)));
        lines.add("Use /cg cloud reset-settings to go back to config.yml only.");
        return lines;
    }

    private static CloudSync current() { return current; }

    // ---- dashboard commands (closed set) --------------------------------------------------

    private void execute(JsonObject command) {
        String id = command.has("id") ? command.get("id").getAsString() : "";
        if (!id.matches("cmd_[A-Za-z0-9]{12,32}") || !executedCommands.add(id)) return;
        String type = command.has("type") ? command.get("type").getAsString() : "";
        String message = null;
        boolean ok;
        try {
            message = CloudCommandExecutor.execute(type, command);
            ok = true;
            if (type.equals("unlink")) {
                CloudCredentials.delete(dir.resolve("credentials.json"));
                credentials = null; claimed = false; recorder.acceptEvents(false);
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException refused) {
            ok = false; message = clip(refused.getMessage(), 500);
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("ok", ok);
        result.addProperty("message", message);
        synchronized (commandResults) { if (commandResults.size() < 64) commandResults.add(result); }
    }

    // ---- status for /cg cloud and /cg doctor ----------------------------------------------

    public static synchronized List<String> describeLines() {
        List<String> lines = new ArrayList<>();
        CloudSync sync = current;
        if (sync == null) {
            lines.add("Cloud: off" + (lastSettings != null && lastSettings.disabledBy != null ? " (" + lastSettings.disabledBy + ")" : "") + ". Nothing is sent.");
            return lines;
        }
        String state = sync.credentials == null ? "registering" : sync.claimed ? "linked" + (sync.networkName == null ? "" : " to \"" + sync.networkName + "\"") : "not linked";
        lines.add("Cloud: on, " + state + ", endpoint " + sync.settings.endpoint.getHost()
                + (sync.lastSyncAt == 0 ? ", no sync yet" : ", last sync " + ((System.currentTimeMillis() - sync.lastSyncAt) / 1000) + "s ago")
                + (sync.lastError == null ? "" : ", last problem: " + sync.lastError) + ".");
        lines.add("Cloud events buffered=" + sync.recorder.buffered() + " dropped=" + sync.recorder.dropped()
                + (sync.claimed ? "" : " (only anonymous totals are sent until linked)"));
        return lines;
    }

    public static synchronized Optional<String> linkUrl() {
        CloudSync sync = current;
        return sync == null || sync.claimed ? Optional.empty() : Optional.ofNullable(sync.linkUrl);
    }

    public static synchronized boolean isRunning() { return current != null; }
    public static synchronized boolean isLinked() { return current != null && current.claimed; }

    /** Tests: run one loop iteration synchronously instead of waiting for the scheduler. */
    static void runOnceForTest() { CloudSync sync; synchronized (CloudSync.class) { sync = current; } if (sync != null) sync.tick(); }
    static CloudRecorder recorderForTest() { synchronized (CloudSync.class) { return current == null ? null : current.recorder; } }

    static Path disabledMarker(Path dataDir) { return dataDir.resolve("cloud").resolve("disabled"); }

    /** Persists the operator's choice so it survives restarts, then applies it immediately. */
    public static synchronized String setDisabledByCommand(boolean disabled) throws IOException {
        if (dataDirectory == null) throw new IllegalStateException("Cloud is not initialised yet.");
        Path marker = disabledMarker(dataDirectory);
        if (disabled) { Files.createDirectories(marker.getParent()); if (!Files.exists(marker)) Files.write(marker, new byte[0]); }
        else Files.deleteIfExists(marker);
        start(dataDirectory, lastConfig, lastPlatform, lastPlatformVersion, lastPluginVersion, lastLog);
        if (disabled) return "Connection Guard Cloud is off and stays off after restarts. Nothing is sent.";
        if (current == null) return "Cloud stays off: " + (lastSettings == null ? "invalid cloud settings" : lastSettings.disabledBy) + ".";
        return "Connection Guard Cloud is on. The first sync happens within a minute.";
    }

    private static String clip(String value, int max) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
