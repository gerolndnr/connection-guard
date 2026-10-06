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
    private static volatile CloudSync current;
    private static ScheduledThreadPoolExecutor worker;
    private static boolean manualSyncRecorded;
    private static long lastManualSyncNanos;
    private static final long MANUAL_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    private static CloudSettings lastSettings;
    private static Path dataDirectory;
    private static Function<String, Object> lastConfig;
    private static DecisionObservation.Platform lastPlatform;
    private static String lastPlatformVersion, lastPluginVersion;
    private static Logger lastLog;
    private static volatile java.util.function.Consumer<CloudLinkNotice> noticeConsole;
    public static void setNoticeConsole(java.util.function.Consumer<CloudLinkNotice> renderer) { noticeConsole = renderer; }
    /** The platform's own reload (/cg reload): re-reads config.yml, layers the managed values, validates, swaps. */
    private static volatile Runnable reloadHook;
    private static final ThreadLocal<CloudSync> applying = new ThreadLocal<>();
    /** Called by each native adapter under its short atomic activation lock, after draft preparation. */
    public static void validateReloadActivation() {
        CloudSync owner = applying.get();
        if (owner != null && !owner.active()) throw new IllegalStateException("Cloud reload retired; active settings preserved.");
    }

    /** Platforms register their reload so dashboard settings can be applied with the same validation as /cg reload. */
    public static void setReloadHook(Runnable hook) { reloadHook = hook; }

    private final CloudSettings settings;
    private final Path dir;
    private final DecisionObservation.Platform platform;
    private final String platformVersion, pluginVersion;
    private final Logger log;
    private final CloudClient client;
    private final CloudRecorder recorder = new CloudRecorder();
    private volatile ScheduledFuture<?> scheduled;
    private SyncRequest manualRequest; // guarded by CloudSync.class; at most one queued/running request
    private volatile boolean closed;
    private final long startedAt = System.currentTimeMillis();
    private final Set<String> executedCommands = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 256; }
    });
    private final List<JsonObject> commandResults = new ArrayList<>();

    private volatile CloudCredentials credentials;
    private volatile boolean claimed;
    private volatile boolean linkStateKnown;
    private final CloudJoinNotices joinNotices;
    private boolean noticeSaveWarned;
    private volatile String networkName, linkCode, linkUrl, lastError;
    private volatile long lastSyncAt;
    private JsonObject pending;
    private final Object pendingLock = new Object();
    private boolean legacyStatus, legacyCoverageStatus;
    private static volatile boolean errorsUnsupported;
    private volatile boolean errorReportsEnabled;
    private boolean cleanupWarned;
    private int failures;
    private volatile CloudManagedConfig managed = CloudManagedConfig.EMPTY;
    private JsonObject configResult;
    private boolean configDeferred;

    private CloudSync(CloudSettings settings, Path dir, DecisionObservation.Platform platform, String platformVersion, String pluginVersion, Logger log) {
        this.settings = settings; this.dir = dir; this.platform = platform; this.platformVersion = platformVersion;
        this.pluginVersion = pluginVersion; this.log = log;
        this.client = new CloudClient(settings.endpoint, pluginVersion);
        this.joinNotices = new CloudJoinNotices(dir);
        errorReportsEnabled = settings.errorReports && !errorsUnsupported;

    }

    // ---- lifecycle ------------------------------------------------------------------------

    /** Starts the link unless switched off. Safe to call again on reload; replaces the running link. */
    public static void start(Path dataDir, Function<String, Object> config, DecisionObservation.Platform platform,
                             String platformVersion, String pluginVersion, Logger log) {
        synchronized (ConnectionGuard.class) {
            synchronized (CloudSync.class) {
                dataDirectory = dataDir; lastConfig = config; lastPlatform = platform;
                lastPlatformVersion = platformVersion; lastPluginVersion = pluginVersion; lastLog = log;
                CloudSettings next;
                try { next = CloudSettings.read(config, Files.exists(disabledMarker(dataDir))); }
                catch (IllegalArgumentException invalid) {
                    stopLocked(); lastSettings = null; PluginErrorReports.configure(false);
                    log.warning("Connection Guard Cloud stays off: invalid settings (values redacted)."); return;
                }
                lastSettings = next;
                PluginErrorReports.configure(next.errorReports && !errorsUnsupported);
                if (next.enabled && current != null && current.settings.endpoint.equals(next.endpoint)
                        && current.settings.networkToken.equals(next.networkToken)) {
                    current.errorReportsEnabled = next.errorReports && !errorsUnsupported;
                    synchronized (current.pendingLock) {
                        if (!current.errorReportsEnabled && current.pending != null) current.pending.remove("errors");
                    }
                    current.errorReportNotice();
                    return;
                }
                stopLocked();
                if (!next.enabled) return;
                if (worker != null && worker.isShutdown() && !worker.isTerminated()) {
                    log.warning("Connection Guard Cloud stays off while its previous worker terminates; retry /cg reload."); return;
                }
                if (worker == null || worker.isTerminated()) {
                    worker = new ScheduledThreadPoolExecutor(1, task -> {
                        Thread thread = new Thread(task, "ConnectionGuard-cloud"); thread.setDaemon(true); return thread;
                    });
                    worker.setRemoveOnCancelPolicy(true);
                }
                CloudSync sync = new CloudSync(next, dataDir.resolve("cloud"), platform, platformVersion, pluginVersion, log);
                current = sync;
                try { sync.begin(); }
                catch (RuntimeException unavailable) {
                    stopLocked(); PluginErrorReports.configure(false); log.warning("Connection Guard Cloud stays off: local state unavailable (values redacted).");
                }
            }
        }
    }

    /** Re-read switches after a native validated reload; unchanged links keep credentials, counters and seq. */
    public static void refresh() {
        synchronized (ConnectionGuard.class) {
            if (dataDirectory != null && lastConfig != null)
                start(dataDirectory, lastConfig, lastPlatform, lastPlatformVersion, lastPluginVersion, lastLog);
        }
    }

    public static void stop() {
        synchronized (ConnectionGuard.class) { synchronized (CloudSync.class) { stopLocked(); PluginErrorReports.configure(false); } }
    }
    private static void stopLocked() {
        CloudSync sync = current; current = null;
        if (sync == null) return;
        sync.closed = true;
        if (sync.manualRequest != null) {
            sync.manualRequest.completion.complete(sync.manualRequest.messages.getString("cloud.off-help"));
            sync.manualRequest = null;
        }
        sync.saveJoinNotices();
        DecisionObservers.setInternal(null);
        sync.recorder.acceptEvents(false);
        if (sync.scheduled != null) sync.scheduled.cancel(true);
        sync.client.close();
    }
    public static void shutdown() {
        noticeConsole = null;
        synchronized (ConnectionGuard.class) {
            synchronized (CloudSync.class) {
                stopLocked();
                PluginErrorReports.shutdown();
                if (worker != null) worker.shutdownNow();
                reloadHook = null; lastConfig = null; dataDirectory = null;
            }
        }
    }
    private boolean active() { return !closed && current == this; }

    /** Configure capture as soon as native config is readable; never starts a Cloud HTTP request. */
    public static void prepareErrorReports(Path dataDir, Function<String, Object> config) {
        try { PluginErrorReports.configure(CloudSettings.read(config, Files.exists(disabledMarker(dataDir))).errorReports && !errorsUnsupported); }
        catch (IllegalArgumentException invalid) { PluginErrorReports.configure(false); }
    }
    static void resetErrorReportsForTest() { PluginErrorReports.shutdown(); errorsUnsupported = false; }

    private void begin() {
        credentials = CloudCredentials.load(dir.resolve("credentials.json"));
        try { joinNotices.load(); }
        catch (IOException invalid) { log.warning("Could not load local dashboard-notice preferences; once-per-start suppression remains active (details redacted)."); }
        managed = CloudManagedConfig.load(dir.getParent());
        if (credentials != null && !credentials.endpoint.equals(settings.endpoint.toString())) credentials = null;
        // A fresh anonymous installation is known unlinked before the first background request.
        // Existing identities and automatic network-token installs await the API's actual state.
        linkStateKnown = credentials == null && settings.networkToken.isEmpty();
        if (linkStateKnown) showConsoleNotice();
        firstRunNotice();
        errorReportNotice();
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

    private void errorReportNotice() {
        if (!errorReportsEnabled) return;
        Path marker = dir.resolve("error-reports-notice-v1");
        if (Files.exists(marker)) return;
        log.info("Connection Guard error reports are on: own exception types and code frames, never message text or player data, are sent with background Cloud sync for error tracking. Set cloud.error-reports: false to turn them off. Privacy: https://connectionguard.net/privacy#plugin");
        try { Files.createDirectories(dir); Files.write(marker, new byte[0]); }
        catch (IOException unreadable) { PluginErrorReports.record(unreadable, PluginErrorReports.Context.STARTUP); }
    }

    private void schedule(long seconds) {
        synchronized (CloudSync.class) {
            if (!active() || worker == null || worker.isShutdown() || manualRequest != null) return;
            if (scheduled != null) scheduled.cancel(false);
            try { scheduled = worker.schedule(this::tick, Math.max(MIN_DELAY, Math.min(MAX_DELAY, seconds)), TimeUnit.SECONDS); }
            catch (RejectedExecutionException stopped) { /* shutting down */ }
        }
    }

    // ---- loop -----------------------------------------------------------------------------

    /** Immediate local acknowledgement, followed by a background completion message. */
    public static final class SyncRequest {
        public final String initialMessage;
        public final CompletableFuture<String> completion = new CompletableFuture<>();
        private final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages;
        private SyncRequest(com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages, String initialMessage) {
            this.messages = messages; this.initialMessage = initialMessage;
        }
        private static SyncRequest rejected(com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages, String message) {
            SyncRequest request = new SyncRequest(messages, message); request.completion.complete(null); return request;
        }
    }

    /** No HTTP, file access or reload on the caller: all work uses the existing single Cloud worker. */
    public static SyncRequest requestSync() { return requestSync(System.nanoTime()); }
    static synchronized SyncRequest requestSync(long nowNanos) {
        com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        CloudSync sync = current;
        if (sync == null || !sync.active() || worker == null || worker.isShutdown())
            return SyncRequest.rejected(messages, messages.getString("cloud.off-help"));
        if (sync.credentials == null || !sync.claimed)
            return SyncRequest.rejected(messages, messages.getString("cloud.registering-help"));
        long remaining = manualSyncRecorded ? MANUAL_INTERVAL - (nowNanos - lastManualSyncNanos) : 0;
        if (remaining > 0 || sync.manualRequest != null)
            return SyncRequest.rejected(messages, messages.text("cloud.sync-wait", Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L)));
        SyncRequest request = new SyncRequest(messages, messages.getString("cloud.sync-start"));
        sync.manualRequest = request;
        if (sync.scheduled != null) { sync.scheduled.cancel(false); sync.scheduled = null; }
        try {
            worker.execute(() -> sync.tickWithReply(request));
            manualSyncRecorded = true; lastManualSyncNanos = nowNanos;
        } catch (RejectedExecutionException stopped) {
            sync.manualRequest = null;
            return SyncRequest.rejected(messages, messages.getString("cloud.off-help"));
        }
        return request;
    }
    static synchronized void resetManualSyncForTest() { manualSyncRecorded = false; lastManualSyncNanos = 0; }
    static synchronized long scheduledDelayForTest() {
        return current == null || current.scheduled == null ? -1 : current.scheduled.getDelay(TimeUnit.MILLISECONDS);
    }
    static synchronized int failuresForTest() { return current == null ? 0 : current.failures; }
    static synchronized void scheduleNowForTest() {
        if (current == null) return;
        if (current.scheduled != null) current.scheduled.cancel(false);
        current.scheduled = worker.schedule(current::tick, 0, TimeUnit.MILLISECONDS);
    }

    private void tick() { tickWithReply(null); }
    private void tickWithReply(SyncRequest command) {
        if (!active()) {
            if (command != null) command.completion.complete(command.messages.getString("cloud.off-help"));
            return;
        }
        if (command != null) {
            synchronized (CloudSync.class) {
                // A command can wait behind an in-flight regular sync. Its cooldown also starts
                // at actual execution, so a long queue wait cannot produce back-to-back requests.
                if (!active()) return; // stopLocked already completes the retired command
                manualSyncRecorded = true; lastManualSyncNanos = System.nanoTime();
            }
        }
        int beforeVersion = managed.version;
        configDeferred = false;
        long next;
        try {
            com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore rules = ConnectionGuard.getRuleStore();
            if (rules != null) {
                try { rules.pruneExpired(System.currentTimeMillis()); cleanupWarned = false; }
                catch (IOException cleanupFailed) {
                    PluginErrorReports.record(cleanupFailed, PluginErrorReports.Context.CACHE);
                    if (!cleanupWarned) log.warning("Connection Guard Cloud: expired rules could not be removed from local storage; expired rules still never permit access (values redacted).");
                    cleanupWarned = true;
                }
            }
            next = credentials == null ? install() : sync();
            if (active() && lastError == null) failures = 0;
        } catch (IOException | RuntimeException failure) {
            if (!active()) return;
            failures++;
            lastError = failure instanceof IOException ? "network unavailable" : "unexpected response";
            next = backoff(-1);
            PluginErrorReports.record(failure, PluginErrorReports.Context.SYNC);
            if (failures == 3) log.warning("Connection Guard Cloud is unreachable; retrying in the background. Logins are not affected.");
            // Remote values and underlying exceptions may contain secrets; never retain their text/cause.
        }
        if (command != null) {
            synchronized (CloudSync.class) { if (manualRequest == command) manualRequest = null; }
        }
        schedule(next);
        if (command != null) {
            String answer;
            if (!active()) answer = command.messages.getString("cloud.off-help");
            else if (credentials == null || !claimed) answer = command.messages.getString("cloud.registering-help");
            else if (lastError != null) answer = command.messages.text("cloud.sync-error", lastError);
            else if (configDeferred) answer = command.messages.getString("cloud.sync-pending");
            else if (configResult != null && !configResult.get("ok").getAsBoolean())
                answer = command.messages.text("cloud.sync-error", configResult.get("message").getAsString());
            else answer = command.messages.text(managed.version > beforeVersion ? "cloud.sync-applied" : "cloud.sync-current", managed.version);
            command.completion.complete(answer);
        }
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
        if (!reply.ok()) { failures++; lastError = "cloud request refused"; return backoff(reply.retryIn()); }
        JsonObject r = reply.body;
        synchronized (ConnectionGuard.class) {
            if (!active()) return MAX_DELAY;
            CloudCredentials created = new CloudCredentials(r.get("install_id").getAsString(), r.get("secret").getAsString(), settings.endpoint.toString());
            created.save(dir.resolve("credentials.json"));
            credentials = created;
            applyLink(r);
            lastError = null;
        }
        return r.get("next_sync_in").getAsLong();
    }

    private long tooOld() {
        lastError = "plugin version too old for the cloud";
        log.warning("This Connection Guard version is too old for Connection Guard Cloud. Update the plugin to keep the dashboard current.");
        return 24 * 3600;
    }

    private long sync() throws IOException {
        // Filtering never holds a lifecycle/admission monitor. Freeze the request separately
        // so a local opt-out cannot mutate Gson's object while HTTP serializes it.
        JsonObject built = pending == null ? buildSync() : null;
        JsonObject request;
        synchronized (pendingLock) {
            if (built != null) pending = built;
            if (!errorReportsEnabled || errorsUnsupported) pending.remove("errors");
            request = pending.deepCopy();
        }
        if (!active()) return MAX_DELAY;
        CloudClient.Reply reply = client.post("/v1/sync", request, credentials.bearer());
        synchronized (ConnectionGuard.class) {
        if (!active()) return MAX_DELAY;
        if (reply.status == 401) {
            // The install was removed (e.g. 30 days unlinked). Start over with a fresh anonymous identity.
            CloudCredentials.delete(dir.resolve("credentials.json"));
            credentials = null; claimed = false; recorder.acceptEvents(false); pending = null;
            return MIN_DELAY;
        }
        if (reply.status == 426) return tooOld();
        if (reply.status == 400 && request.has("errors")) {
            errorsUnsupported = true;
            errorReportsEnabled = false;
            PluginErrorReports.configure(false);
            synchronized (pendingLock) { pending.remove("errors"); }
            lastError = "error reports disabled for an older cloud until restart";
            return MIN_DELAY; // SAME seq, events and counters; retry only once without errors.
        }
        if (reply.status == 400 && !legacyCoverageStatus && pending.getAsJsonObject("status").has("vpn_unchecked_allowed")) {
            // Keep rule-expiry capabilities when only the new aggregate is unsupported.
            legacyCoverageStatus = true;
            pending.getAsJsonObject("status").remove("vpn_unchecked_allowed");
            lastError = "cloud API does not yet support VPN coverage status";
            return MIN_DELAY;
        }
        if (reply.status == 400 && !legacyStatus && pending.getAsJsonObject("status").has("capabilities")) {
            // Older protocol-1 APIs reject unknown status fields. Retry the SAME decision batch
            // and sequence without capabilities, rather than draining/dropping or recounting it.
            legacyStatus = true;
            pending.getAsJsonObject("status").remove("capabilities");
            pending.getAsJsonObject("status").remove("vpn_unchecked_allowed");
            lastError = "using older cloud status format";
            return MIN_DELAY;
        }
        if (reply.status == 400 || reply.status == 413) { pending = null; lastError = "server rejected a sync"; return backoff(-1); }
        if (reply.status == 429) {
            // SYNC_LIMITER is congestion, not a failed link. Preserve the pending seq/batch and
            // respect its retry delay without increasing exponential transport backoff.
            lastError = "cloud sync rate limit; retrying in the background";
            return reply.retryIn() > 0 ? reply.retryIn() : 60;
        }
        if (!reply.ok()) { failures++; lastError = "cloud request refused"; return backoff(reply.retryIn()); }
        lastError = null;
        if (pending.getAsJsonObject("status").get("config_result").isJsonObject()) configResult = null; // delivered
        pending = null;
        lastSyncAt = System.currentTimeMillis();
        JsonObject r = reply.body;
        applyLink(r);
        recorder.acceptEvents(claimed && r.get("accept_events").getAsBoolean());
        }
        JsonObject r = reply.body;
        JsonElement commands = r.get("commands");
        if (commands != null && commands.isJsonArray()) {
            if (commands.getAsJsonArray().size() > 64) throw new IllegalArgumentException("Too many cloud commands.");
            for (JsonElement c : commands.getAsJsonArray()) { if (!active()) return MAX_DELAY; execute(c.getAsJsonObject()); }
        }
        JsonElement config = r.get("config");
        synchronized (CloudManagedConfig.reloadLock()) {
            if (!active()) return MAX_DELAY;
            if (claimed && credentials != null && config != null && config.isJsonObject() && applyConfig(config.getAsJsonObject())) return MIN_DELAY;
        }
        return r.get("next_sync_in").getAsLong();
    }

    private void applyLink(JsonObject r) {
        boolean nowClaimed = r.get("claimed").getAsBoolean();
        String code = r.has("link_code") && !r.get("link_code").isJsonNull() ? r.get("link_code").getAsString() : null;
        String url = r.has("link_url") && !r.get("link_url").isJsonNull() ? r.get("link_url").getAsString() : null;
        networkName = r.has("network_name") && !r.get("network_name").isJsonNull() ? safeLabel(r.get("network_name").getAsString(), 100) : null;
        if (code != null && !code.matches("[0-9A-Z]{4}-[0-9A-Z]{4}")) throw new IllegalArgumentException("Invalid cloud reply.");
        if (url != null) {
            java.net.URI link = CloudSettings.endpoint(url);
            if (code == null || !link.getPath().endsWith("/link/" + code) || url.length() > 512) throw new IllegalArgumentException("Invalid cloud reply.");
        }
        if (nowClaimed && !claimed) log.info("Connection Guard Cloud: linked to " + (networkName == null ? "your network" : "\"" + networkName + "\"") + ".");
        boolean changed = !Objects.equals(url, linkUrl);
        claimed = nowClaimed; linkCode = code; linkUrl = url;
        linkStateKnown = true;
        if (!nowClaimed && url != null && changed) showConsoleNotice();
    }

    private void showConsoleNotice() {
        CloudLinkNotice notice = new CloudLinkNotice(this, linkUrl, "console");
        java.util.function.Consumer<CloudLinkNotice> renderer = noticeConsole;
        if (renderer != null) {
            try { renderer.accept(notice); return; }
            catch (RuntimeException | LinkageError unavailable) { PluginErrorReports.record(unavailable, PluginErrorReports.Context.OTHER); }
        }
        for (String line : notice.consoleLines()) log.info(line);
    }
    public static synchronized boolean noticeCurrent(CloudLinkNotice notice) {
        CloudSync sync = current;
        return notice != null && sync != null && sync == notice.owner && sync.active() && sync.linkStateKnown
                && !sync.claimed && Objects.equals(sync.linkUrl, notice.rawUrl);
    }
    public static synchronized boolean awaitingLinkState() { return current != null && !current.linkStateKnown; }
    /** Called only after join, with permissions and connection state checked by the native adapter. */
    public static synchronized Optional<CloudLinkNotice> takeJoinNotice(UUID uuid, boolean authorized, boolean proxyBackend) {
        CloudSync sync = current;
        if (!authorized || proxyBackend || sync == null || !sync.active() || !sync.linkStateKnown || sync.claimed || !sync.joinNotices.reserve(uuid))
            return Optional.empty();
        // Persist on the background worker. Never do filesystem/network I/O in the player callback.
        try { worker.execute(sync::saveJoinNotices); }
        catch (RejectedExecutionException retired) { /* A clean stop also flushes local suppression. */ }
        return Optional.of(new CloudLinkNotice(sync, sync.linkUrl, "join"));
    }
    private void saveJoinNotices() {
        try { joinNotices.save(); }
        catch (IOException invalid) {
            if (!noticeSaveWarned) { noticeSaveWarned = true; log.warning("Could not save dashboard-notice preferences; join hints may repeat after restart (details redacted)."); }
        }
    }

    private JsonObject buildSync() throws IOException {
        long sequence = Math.max(System.currentTimeMillis(), Math.addExact(credentials.sequence, 1));
        CloudCredentials next = credentials.withSequence(sequence);
        next.save(dir.resolve("credentials.json"));
        credentials = next;
        // seq: the server ignores a seq it has already counted, so a retried body is never double counted.
        CloudRecorder.Batch batch = recorder.drain(MAX_EVENTS_PER_SYNC, System.currentTimeMillis());
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("seq", sequence);
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
        if (errorReportsEnabled && !errorsUnsupported) {
            JsonArray errors = PluginErrorReports.drainForSync();
            if (errors.size() > 0) body.add("errors", errors);
        }
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
        if (!legacyStatus && !legacyCoverageStatus) {
            com.github.gerolndnr.connectionguard.core.lookup.UncheckedVpnAdmissions.Snapshot coverage = ConnectionGuard.uncheckedVpnAdmissions().snapshot();
            JsonObject unchecked = new JsonObject();
            unchecked.addProperty("total", Math.min(1_000_000_000L, coverage.total));
            unchecked.addProperty("since_summary", Math.min(1_000_000_000L, coverage.sinceSummary));
            unchecked.addProperty("window_seconds", Math.min(1_000_000_000L, coverage.windowSeconds));
            JsonObject reasons = new JsonObject();
            coverage.reasons.forEach((reason, count) -> reasons.addProperty(reason.name(), Math.min(1_000_000_000L, count)));
            unchecked.add("reasons", reasons);
            s.add("vpn_unchecked_allowed", unchecked);
        }
        if (!legacyStatus) {
            JsonArray capabilities = new JsonArray(); capabilities.add("rule_expiry"); capabilities.add("sync_command");
            s.add("capabilities", capabilities);
        }
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
        final int version;
        try { version = CloudManagedConfig.desiredVersion(desired); }
        catch (IllegalArgumentException invalid) { PluginErrorReports.record(invalid, PluginErrorReports.Context.SYNC); lastError = "malformed settings response"; return false; }
        if (version <= managed.version) return false;
        Runnable hook = reloadHook;
        if (hook == null) { configResult = result(version, false, "This server cannot apply dashboard settings."); return true; }
        final CloudManagedConfig next;
        try {
            next = CloudManagedConfig.desired(managed, desired);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException invalid) {
            PluginErrorReports.record(invalid, PluginErrorReports.Context.RELOAD);
            configResult = result(version, false, "Malformed or unsupported settings; values redacted.");
            log.warning("Connection Guard Cloud: refused dashboard settings v" + version + ": " + configResult.get("message").getAsString());
            return true;
        }
        Path dataDir = dir.getParent();
        byte[] previous;
        try { previous = CloudManagedConfig.write(dataDir, next); }
        catch (IOException failure) { PluginErrorReports.record(failure, PluginErrorReports.Context.RELOAD); configResult = result(version, false, "Could not write the settings file on the server."); return true; }
        try {
            applying.set(this);
            try { hook.run(); } finally { applying.remove(); }
            if (!active()) throw new IllegalStateException("Cloud reload retired.");
            managed = next;
            configResult = result(version, true, null);
            log.info("Connection Guard Cloud: applied dashboard settings v" + version + (next.values.isEmpty() ? " (config.yml only)." : "."));
        } catch (IllegalStateException busy) {
            // Lookups in flight: nothing changed; the next sync delivers the same version again.
            rollback(dataDir, previous);
            configDeferred = true;
        } catch (RuntimeException invalid) {
            rollback(dataDir, previous);
            configResult = result(version, false, "The server rejected these settings; check /cg doctor and the local configuration (values redacted).");
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
    public static String resetSettingsLocally() {
        synchronized (CloudManagedConfig.reloadLock()) {
        if (dataDirectory == null) throw new IllegalStateException("Not initialised yet.");
        CloudManagedConfig current = CloudManagedConfig.load(dataDirectory);
        if (current.values.isEmpty()) return "No dashboard settings are active; config.yml applies.";
        Runnable hook = reloadHook;
        byte[] previous;
        try { previous = CloudManagedConfig.write(dataDirectory, new CloudManagedConfig(current.version, new java.util.LinkedHashMap<>())); }
        catch (IOException failure) { return "Could not change the settings file; check file permissions."; }
        try { if (hook == null) throw new IllegalStateException(); hook.run(); }
        catch (RuntimeException rejected) {
            try { CloudManagedConfig.restore(dataDirectory, previous); } catch (IOException ignored) { /* reported below */ }
            return "Reload refused; dashboard settings kept. Try again in a moment (values redacted).";
        }
        if (current() != null) current().managed = CloudManagedConfig.load(dataDirectory);
        return "Dashboard settings removed; config.yml applies again. The dashboard shows this after the next sync.";
        }
    }

    public static synchronized List<String> describeSettings() {
        List<String> lines = new ArrayList<>();
        com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        if (dataDirectory == null) return lines;
        CloudManagedConfig current = CloudManagedConfig.load(dataDirectory);
        if (current.values.isEmpty()) { lines.add(messages.getString("cloud.settings-local")); return lines; }
        lines.add(messages.text("cloud.settings-header", current.version));
        for (String path : current.values.keySet()) lines.add("  " + path + (CloudManagedConfig.isSecret(path) ? " (secret)" : " = " + current.values.get(path)));
        lines.add(messages.getString("cloud.settings-reset-help"));
        return lines;
    }

    private static CloudSync current() { return current; }

    // ---- dashboard commands (closed set) --------------------------------------------------

    private void execute(JsonObject command) {
        String id = command.has("id") && command.get("id").isJsonPrimitive() && command.get("id").getAsJsonPrimitive().isString() ? command.get("id").getAsString() : "";
        if (!id.matches("cmd_[A-Za-z0-9]{12,32}") || !executedCommands.add(id)) return;
        String type = command.has("type") && command.get("type").isJsonPrimitive() && command.get("type").getAsJsonPrimitive().isString() ? command.get("type").getAsString() : "";
        String message = null;
        boolean ok;
        try {
            if (!claimed) throw new IllegalStateException("Server is not linked.");
            message = CloudCommandExecutor.execute(type, command);
            ok = true;
            if (type.equals("unlink")) {
                CloudCredentials.delete(dir.resolve("credentials.json"));
                credentials = null; claimed = false; recorder.acceptEvents(false);
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException refused) {
            PluginErrorReports.record(refused, PluginErrorReports.Context.COMMAND);
            ok = false; message = "Command refused (values redacted).";
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
        lines.add(PluginErrorReports.describe() + (errorsUnsupported ? " (older cloud: disabled until restart)" : ""));
        CloudSync sync = current;
        lines.add(ConnectionGuard.uncheckedVpnAdmissions().snapshot().describe());
        com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        if (sync == null) {
            lines.add(messages.text("cloud.status-off", lastSettings != null && lastSettings.disabledBy != null ? " (" + lastSettings.disabledBy + ")" : ""));
            return lines;
        }
        String state = messages.getString(sync.credentials == null ? "cloud.registering" : sync.claimed ? "cloud.linked" : "cloud.unlinked");
        lines.add(messages.text("cloud.status-on", state, sync.settings.endpoint.getHost(),
                sync.lastSyncAt == 0 ? messages.getString("cloud.no-sync") : messages.text("cloud.last-sync", (System.currentTimeMillis() - sync.lastSyncAt) / 1000),
                sync.lastError == null ? "" : messages.text("cloud.last-error", sync.lastError)));
        if (sync.legacyCoverageStatus) lines.add("Cloud API does not yet support VPN coverage counters; local counts above remain accurate.");
        lines.add("Cloud events buffered=" + sync.recorder.buffered() + " dropped=" + sync.recorder.dropped()
                + (sync.claimed ? "" : messages.getString("cloud.anonymous")));
        return lines;
    }

    public static synchronized Optional<String> linkUrl() {
        CloudSync sync = current;
        return sync == null || sync.claimed ? Optional.empty() : Optional.ofNullable(sync.linkUrl);
    }
    public static synchronized Optional<String> linkUrl(String source) { return linkUrl().map(url -> CloudLinkNotice.sourced(url, source)); }

    public static synchronized boolean isRunning() { return current != null; }
    public static synchronized boolean isLinked() { return current != null && current.claimed; }

    /** Tests: run one loop iteration synchronously instead of waiting for the scheduler. */
    static void runOnceForTest() { CloudSync sync; synchronized (CloudSync.class) { sync = current; } if (sync != null) sync.tick(); }
    static CloudRecorder recorderForTest() { synchronized (CloudSync.class) { return current == null ? null : current.recorder; } }

    static Path disabledMarker(Path dataDir) { return dataDir.resolve("cloud").resolve("disabled"); }

    /** Persists the operator's choice so it survives restarts, then applies it immediately. */
    public static String setDisabledByCommand(boolean disabled) throws IOException {
        synchronized (ConnectionGuard.class) {
        if (dataDirectory == null) throw new IllegalStateException("Cloud is not initialised yet.");
        Path marker = disabledMarker(dataDirectory);
        if (disabled) { Files.createDirectories(marker.getParent()); if (!Files.exists(marker)) Files.write(marker, new byte[0]); }
        else Files.deleteIfExists(marker);
        start(dataDirectory, lastConfig, lastPlatform, lastPlatformVersion, lastPluginVersion, lastLog);
        if (disabled) return "Connection Guard Cloud is off and stays off after restarts. Nothing is sent.";
        if (current == null) return "Cloud stays off: " + (lastSettings == null ? "invalid cloud settings" : lastSettings.disabledBy) + ".";
        return "Connection Guard Cloud is on. The first sync happens within a minute.";
        }
    }

    private static String safeLabel(String value, int max) {
        return clip(value.replaceAll("[\\p{Cntrl}\\p{Cf}]", ""), max);
    }

    private static String clip(String value, int max) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
