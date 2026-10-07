package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig;
import com.github.gerolndnr.connectionguard.core.migration.CompetitorMigration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Operator-only local file work on one bounded worker; never part of login detection. */
public final class MigrationCommands {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "connection-guard-migration"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String, CompetitorMigration.Plan> PREVIEWS = new LinkedHashMap<>();
    private MigrationCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply, boolean console) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("migrate")) return false;
        if (!permission.test("connectionguard.command.migrate")) { reply.accept(ConnectionGuard.getMessages().getString("ops.permission")); return true; }
        if (ConnectionGuard.getActiveDraft() == null || ConnectionGuard.getActiveDraft().dataPath == null) { reply.accept("Migration unavailable: no active plugin data directory."); return true; }
        Path directory = ConnectionGuard.getActiveDraft().dataPath;
        if (args.length == 3 && args[1].equalsIgnoreCase("preview") && CompetitorMigration.SOURCES.contains(args[2].toLowerCase(Locale.ROOT))) {
            submit(reply, () -> {
                CompetitorMigration.Plan plan;
                synchronized (CloudManagedConfig.reloadLock()) { plan = new CompetitorMigration(directory).preview(args[2].toLowerCase(Locale.ROOT)); }
                synchronized (PREVIEWS) { if (PREVIEWS.size() == 5) PREVIEWS.remove(PREVIEWS.keySet().iterator().next()); PREVIEWS.put(plan.id(), plan); }
                reply.accept("Migration preview " + plan.id() + " (no files/keys/IPs applied or transmitted):");
                // Do not flood chat with every unresolved name; the complete plan is rechecked on apply.
                plan.report().stream().limit(console ? Integer.MAX_VALUE : 32).forEach(reply);
                if (!console && plan.report().size() > 32) reply.accept("Additional review notes=" + (plan.report().size() - 32) + "; use the console for the full preview.");
                plan.blockers().stream().limit(console ? Integer.MAX_VALUE : 16).forEach(line -> reply.accept("BLOCKER: " + line));
                if (plan.ready()) reply.accept("After reviewing recipients/semantics, console: /cg migrate apply " + plan.id() + " reviewed. Remove competitor JARs and restart; backups are retained.");
            }); return true;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("apply") && args[2].matches("[0-9a-f]{20}") && args[3].equals("reviewed")) {
            if (!console) { reply.accept("Stage migrations from the server console after reviewing the preview."); return true; }
            submit(reply, () -> {
                CompetitorMigration.Plan plan; synchronized (PREVIEWS) { plan = PREVIEWS.get(args[2]); }
                if (plan == null) throw new IOException("Preview token unavailable; run /cg migrate preview <source> again.");
                synchronized (CloudManagedConfig.reloadLock()) { new CompetitorMigration(directory).stage(plan); }
                reply.accept("Migration staged. Active checks are unchanged. Remove competitor JARs, stop cleanly, then restart. Cancel before restart: /cg migrate cancel.");
            }); return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            if (!console) { reply.accept("Cancel migrations from the server console."); return true; }
            submit(reply, () -> { synchronized (CloudManagedConfig.reloadLock()) { new CompetitorMigration(directory).cancel(); }
                reply.accept("Staged migration cancelled; active files/checks retained."); }); return true;
        }
        if (args.length == 1) {
            submit(reply, () -> { List<String> sources = new CompetitorMigration(directory).discover();
                reply.accept("Detected migration sources: " + (sources.isEmpty() ? "none" : String.join(", ", sources)));
                reply.accept("Supported: " + String.join(", ", CompetitorMigration.SOURCES)); usage(reply); }); return true;
        }
        usage(reply); return true;
    }
    private interface Work { void run() throws IOException; }
    private static void submit(Consumer<String> reply, Work work) {
        try { WORKER.execute(() -> {
            try { work.run(); }
            catch (IOException error) { reply.accept("Migration rejected: " + error.getMessage()); }
            catch (RuntimeException | LinkageError invalid) { reply.accept("Migration rejected: invalid/unsupported input or unavailable driver (values redacted). Active checks retained."); }
        }); } catch (RejectedExecutionException busy) { reply.accept("Migration worker busy; retry later."); }
    }
    private static void usage(Consumer<String> reply) { reply.accept("/cg migrate | /cg migrate preview <source> | /cg migrate apply <preview-id> reviewed | /cg migrate cancel"); }
    public static void shutdown() { synchronized (PREVIEWS) { PREVIEWS.clear(); } WORKER.getQueue().clear(); }
}
