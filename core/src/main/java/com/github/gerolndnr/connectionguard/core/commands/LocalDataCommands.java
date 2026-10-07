package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.local.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Operator-only bounded file/download work on a separate single worker, never on a login/event thread. */
public final class LocalDataCommands {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "connection-guard-local-data"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> { Thread thread = new Thread(task, "connection-guard-local-timer"); thread.setDaemon(true); return thread; });
        timer.setRemoveOnCancelPolicy(true); return timer;
    }
    private static ScheduledFuture<?> scheduled, intelScheduled;
    public static synchronized void configure(ProviderConfiguration draft) {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
        if (intelScheduled != null) intelScheduled.cancel(false);
        intelScheduled = null;
        boolean fetchIntel = draft.intelSettings.updateHours > 0 && !"false".equalsIgnoreCase(System.getenv("CONNECTIONGUARD_INTEL_REFRESH"));
        if (draft.intelStore != null && (draft.intelBootstrapPending || fetchIntel)) {
            intelScheduled = TIMER.scheduleWithFixedDelay(() -> {
                if (ConnectionGuard.getActiveDraft() != draft) return;
                long now = System.currentTimeMillis();
                if (!draft.intelBootstrapPending && draft.intelSnapshot.readiness(now) == com.github.gerolndnr.connectionguard.core.lookup.FailureReason.NONE
                        && now - draft.intelSnapshot.fetchedAt < TimeUnit.HOURS.toMillis(draft.intelSettings.updateHours)) return;
                try { WORKER.execute(() -> { if (draft.intelBootstrapPending) bootstrapIntel(draft); else automaticIntel(draft); }); }
                catch (RejectedExecutionException busy) { alert("Intel updater busy; last verified generation retained."); }
            }, 0, Math.max(1, draft.intelSettings.updateHours), TimeUnit.HOURS);
        }
        if (draft.localUpdateHours == 0 || draft.localStore == null) return;
        scheduled = TIMER.scheduleWithFixedDelay(() -> {
            if (ConnectionGuard.getActiveDraft() != draft) return;
            try { WORKER.execute(() -> automatic(draft)); }
            catch (RejectedExecutionException busy) { alert("Local data updater is busy; active generation preserved."); }
        }, draft.localUpdateHours, draft.localUpdateHours, TimeUnit.HOURS);
    }
    private static void bootstrapIntel(ProviderConfiguration current) {
        if (ConnectionGuard.getActiveDraft() != current) return;
        // Reverification and index construction happen entirely on the local-data worker.
        try { activate(current); }
        catch (Exception unavailable) {
            alert("Saved Intel load/activation unavailable; Intel remains UNKNOWN. Other protection stays active; retry /cg local reload.");
            TIMER.schedule(() -> {
                if (ConnectionGuard.getActiveDraft() != current) return;
                try { WORKER.execute(() -> bootstrapIntel(current)); }
                catch (RejectedExecutionException busy) { alert("Intel bootstrap busy; retry /cg local reload."); }
            }, 1, TimeUnit.MINUTES);
        }
    }
    private static void automaticIntel(ProviderConfiguration current) {
        if (ConnectionGuard.getActiveDraft() != current) return;
        try { current.intelStore.update(); }
        catch (Exception unavailable) { alert("Connection Guard Intel refresh failed; last signed generation retained. Missing/stale data remains UNKNOWN; see /cg doctor."); return; }
        publishOrRetry(current);
    }
    private static void publishOrRetry(ProviderConfiguration current) {
        try { activate(current); }
        catch (Exception busy) {
            alert("Validated local data staged; active generation preserved. Activation will retry in one minute.");
            TIMER.schedule(() -> {
                if (ConnectionGuard.getActiveDraft() != current) return;
                try { WORKER.execute(() -> {
                    try { activate(current); } catch (Exception unavailable) { alert("Staged local activation unavailable; retry /cg local reload."); }
                }); } catch (RejectedExecutionException ignored) { alert("Staged local activation busy; retry /cg local reload."); }
            }, 1, TimeUnit.MINUTES);
        }
    }
    private static void automatic(ProviderConfiguration current) {
        boolean stored = false;
        for (LocalSource source : current.localStore.sources()) {
            if (ConnectionGuard.getActiveDraft() != current) return;
            if (source.downloadUrl().isEmpty()) continue;
            try { LocalListDownloader.update(current.localStore, source.id); stored = true; }
            catch (Exception unavailable) { alert("Local list update unavailable; previous source manifest preserved."); }
        }
        if (stored) publishOrRetry(current);
    }

    private static void alert(String message) { if (ConnectionGuard.getLogger() != null) ConnectionGuard.getLogger().warning(message); }
    private static void activate(ProviderConfiguration current) {
        ProviderConfiguration draft = current.refreshLocal();
        synchronized (ConnectionGuard.class) {
            if (ConnectionGuard.getActiveDraft() != current) throw new IllegalStateException("Configuration changed.");
            ConnectionGuard.applyProviders(draft);
        }
        if (draft.intelSnapshot.proxyState == IntelSnapshot.ProxyState.SKIPPED)
            alert("Connection Guard Intel optional PROXY list rejected/unavailable; verified VPN/TOR/RELAY/HOSTING retained. See /cg local status.");
    }
    private LocalDataCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        if (args.length == 0 || !args[0].equalsIgnoreCase("local")) return false;
        if (!permission.test("connectionguard.command.local")) { reply.accept(messages.getString("ops.permission")); return true; }
        ProviderConfiguration current = ConnectionGuard.getActiveDraft();
        if (current == null || current.localStore == null && current.intelStore == null) { reply.accept(messages.getString("ops.local-disabled")); return true; }
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            if (current.intelSettings.enabled) reply.accept(current.intelSnapshot.describe(System.currentTimeMillis()));
            for (LocalSnapshot snapshot : current.localSnapshots) reply.accept(snapshot.describe(System.currentTimeMillis()));
            return true;
        }
        boolean prepare = args.length == 2 && args[1].equalsIgnoreCase("prepare");
        boolean reload = args.length == 2 && args[1].equalsIgnoreCase("reload");
        boolean update = args.length == 3 && args[1].equalsIgnoreCase("update");
        boolean importing = args.length == 5 && args[1].equalsIgnoreCase("import");
        if (!prepare && !reload && !update && !importing) {
            reply.accept(messages.getString("ops.local-usage")); return true;
        }
        final long asOf;
        try {
            if (prepare && current.localStore == null) throw new IllegalArgumentException("No operator local sources configured.");
            if ((update || importing) && !(update && args[2].equals(IntelSnapshot.ID) && current.intelStore != null)) {
                if (current.localStore == null) throw new IllegalArgumentException("No operator local sources configured.");
                current.localStore.source(args[2]);
            }
            asOf = importing ? Instant.parse(args[4]).toEpochMilli() : 0;
        } catch (RuntimeException invalid) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(invalid, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); reply.accept(messages.getString("ops.local-invalid")); return true; }
        try {
            WORKER.execute(() -> {
                boolean stored = false;
                try {
                    if (ConnectionGuard.getActiveDraft() != current) throw new IllegalStateException("Configuration changed.");
                    if (prepare) { current.localStore.prepareInbox(); reply.accept(messages.getString("ops.local-prepared")); return; }
                    if (update) { if (args[2].equals(IntelSnapshot.ID) && current.intelStore != null) current.intelStore.update(); else LocalListDownloader.update(current.localStore, args[2]); stored = true; }
                    if (importing) { current.localStore.importFile(args[2], args[3], asOf, System.currentTimeMillis()); stored = true; }
                    activate(current);
                    reply.accept(messages.getString("ops.local-activated"));
                } catch (Exception invalid) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(invalid, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND);
                    reply.accept(stored ? messages.getString("ops.local-stored")
                            : messages.getString("ops.local-rejected"));
                }
            });
        } catch (RejectedExecutionException busy) { reply.accept(messages.getString("ops.local-busy")); }
        return true;
    }
    public static void shutdown() { TIMER.shutdownNow(); WORKER.shutdownNow(); }
}
