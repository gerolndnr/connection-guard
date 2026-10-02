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
    private static ScheduledFuture<?> scheduled;
    public static synchronized void configure(ProviderConfiguration draft) {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
        if (draft.localUpdateHours == 0 || draft.localStore == null) return;
        scheduled = TIMER.scheduleWithFixedDelay(() -> {
            if (ConnectionGuard.getActiveDraft() != draft) return;
            try { WORKER.execute(() -> automatic(draft)); }
            catch (RejectedExecutionException busy) { alert("Local data updater is busy; active generation preserved."); }
        }, draft.localUpdateHours, draft.localUpdateHours, TimeUnit.HOURS);
    }
    private static void automatic(ProviderConfiguration current) {
        boolean stored = false;
        for (LocalSource source : current.localStore.sources()) {
            if (ConnectionGuard.getActiveDraft() != current) return;
            if (source.downloadUrl().isEmpty()) continue;
            try { LocalListDownloader.update(current.localStore, source.id); stored = true; }
            catch (Exception unavailable) { alert("Local list update unavailable; previous source manifest preserved."); }
        }
        if (stored) try { activate(current); }
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
    private static void alert(String message) { if (ConnectionGuard.getLogger() != null) ConnectionGuard.getLogger().warning(message); }
    private static void activate(ProviderConfiguration current) {
        ProviderConfiguration draft = current.refreshLocal();
        synchronized (ConnectionGuard.class) {
            if (ConnectionGuard.getActiveDraft() != current) throw new IllegalStateException("Configuration changed.");
            ConnectionGuard.applyProviders(draft);
        }
    }
    private LocalDataCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("local")) return false;
        if (!permission.test("connectionguard.command.local")) { reply.accept("You do not have permission for this command."); return true; }
        ProviderConfiguration current = ConnectionGuard.getActiveDraft();
        if (current == null || current.localStore == null) { reply.accept("Local data is disabled. Configure attributed sources and choose local VPN and/or Local geo first."); return true; }
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            for (LocalSnapshot snapshot : current.localSnapshots) reply.accept(snapshot.describe(System.currentTimeMillis()));
            return true;
        }
        boolean prepare = args.length == 2 && args[1].equalsIgnoreCase("prepare");
        boolean reload = args.length == 2 && args[1].equalsIgnoreCase("reload");
        boolean update = args.length == 3 && args[1].equalsIgnoreCase("update");
        boolean importing = args.length == 5 && args[1].equalsIgnoreCase("import");
        if (!prepare && !reload && !update && !importing) {
            reply.accept("Usage: /cg local status|prepare|reload; /cg local update <source-id>; /cg local import <source-id> <inbox-file> <ISO-8601-as-of>"); return true;
        }
        final long asOf;
        try {
            if (update || importing) current.localStore.source(args[2]);
            asOf = importing ? Instant.parse(args[4]).toEpochMilli() : 0;
        } catch (RuntimeException invalid) { reply.accept("Invalid source ID or date. Use UTC ISO-8601, e.g. 2026-10-02T09:00:00Z."); return true; }
        try {
            WORKER.execute(() -> {
                boolean stored = false;
                try {
                    if (ConnectionGuard.getActiveDraft() != current) throw new IllegalStateException("Configuration changed.");
                    if (prepare) { current.localStore.prepareInbox(); reply.accept("Private local-data/inbox is ready inside the plugin data directory."); return; }
                    if (update) { LocalListDownloader.update(current.localStore, args[2]); stored = true; }
                    if (importing) { current.localStore.importFile(args[2], args[3], asOf, System.currentTimeMillis()); stored = true; }
                    activate(current);
                    reply.accept("Validated local generation activated; cache namespace changed. Inspect /cg local status for actual age and readiness.");
                } catch (Exception invalid) {
                    reply.accept(stored ? "Validated data stored; active generation preserved. Wait for active lookups, then /cg local reload (details redacted)."
                            : "Local data operation rejected; active generation preserved (details redacted).");
                }
            });
        } catch (RejectedExecutionException busy) { reply.accept("Local data worker is busy; retry later."); }
        return true;
    }
    public static void shutdown() { TIMER.shutdownNow(); WORKER.shutdownNow(); }
}
