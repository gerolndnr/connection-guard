package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Counts CG-allowed logins without a conclusive VPN result, independently of optional observers.
 * No addresses, identities, provider IDs or exception text are retained. Reload never resets counts. */
public final class UncheckedVpnAdmissions implements AutoCloseable {
    public static final long SUMMARY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private final LongSupplier clock;
    private final Map<FailureReason, Long> totals = new EnumMap<>(FailureReason.class);
    private final Map<FailureReason, Long> pending = new EnumMap<>(FailureReason.class);
    private long total, pendingCount, since;
    private ScheduledExecutorService timer;
    private Consumer<String> warning;

    public UncheckedVpnAdmissions() { this(System::currentTimeMillis); }
    UncheckedVpnAdmissions(LongSupplier clock) { this.clock = clock; since = clock.getAsLong(); }

    /** Called once at completion of a real native login, never for explain/cache/provider queries. */
    public synchronized void allowed(FailureReason reason) {
        if (reason == null || reason == FailureReason.NONE) return;
        total++; pendingCount++;
        totals.merge(reason, 1L, Long::sum); pending.merge(reason, 1L, Long::sum);
    }

    public static FailureReason unresolved(VpnResult vpn, boolean exempt) {
        if (exempt || vpn == null || vpn.getStatus() != ProviderVote.Status.UNKNOWN) return null;
        // Each login contributes once even if several sources failed. The first voting failure in
        // configured order preserves a quota failure when all of its fallbacks are unavailable.
        for (ProviderVote vote : vpn.getVotes())
            if (vote.isVoting() && vote.getStatus() == ProviderVote.Status.UNKNOWN && vote.getReason() != FailureReason.NONE)
                return vote.getReason();
        return vpn.getSourceReason() == FailureReason.NONE ? FailureReason.NO_EVIDENCE : vpn.getSourceReason();
    }

    /** Separate timer: five-minute warnings cannot block lookup retirement or reload. */
    public synchronized void start(Consumer<String> warning) {
        if (timer != null) return;
        this.warning = warning;
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ConnectionGuard-vpn-coverage"); thread.setDaemon(true); return thread;
        });
        timer.scheduleWithFixedDelay(this::summarize, SUMMARY_MILLIS, SUMMARY_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Fixed reporting windows, plus a final partial window at shutdown. */
    public void summarize() {
        String line; Consumer<String> output;
        synchronized (this) {
            long seconds = Math.max(0, clock.getAsLong() - since) / 1000;
            line = pendingCount == 0 ? null : "Connection Guard protection degraded: " + pendingCount
                    + " logins admitted without a conclusive VPN check in the last " + seconds
                    + " seconds; reasons=" + pending + "; total=" + total + ". Check /cg doctor and /cg cloud status.";
            output = warning;
            if (output == null) return;
            pending.clear(); pendingCount = 0; since = clock.getAsLong();
        }
        if (line != null) {
            try { output.accept(line); }
            catch (RuntimeException ignored) { /* Logging cannot stop future reporting or affect logins. */ }
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(total, pendingCount, Math.max(0, clock.getAsLong() - since) / 1000, totals, pending);
    }
    public static final class Snapshot {
        public final long total, sinceSummary, windowSeconds;
        public final Map<FailureReason, Long> reasons, windowReasons;
        private Snapshot(long total, long pending, long seconds, Map<FailureReason, Long> reasons, Map<FailureReason, Long> windowReasons) {
            this.total = total; sinceSummary = pending; windowSeconds = seconds;
            this.reasons = Collections.unmodifiableMap(new EnumMap<>(reasons));
            this.windowReasons = Collections.unmodifiableMap(new EnumMap<>(windowReasons));
        }
        public String describe() {
            return "VPN uncheckedAllowed=" + total + " sinceSummary=" + sinceSummary + " windowSeconds=" + windowSeconds
                    + " reasons=" + reasons + " (CG allowed without a conclusive VPN result; exemptions and denies excluded)";
        }
    }
    @Override public void close() {
        ScheduledExecutorService previous;
        synchronized (this) { previous = timer; timer = null; }
        if (previous != null) previous.shutdownNow();
        summarize();
    }
}
