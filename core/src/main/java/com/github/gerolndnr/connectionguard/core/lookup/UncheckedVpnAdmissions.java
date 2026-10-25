package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Counts CG-allowed logins without a conclusive VPN result, independently of optional observers.
 * No addresses, identities or exception text are retained, only the address family and which configured source
 * left which kind of hint ("source.hosting", "source.no_evidence", "source.failed"). Reload never resets counts. */
public final class UncheckedVpnAdmissions implements AutoCloseable {
    public static final long SUMMARY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    /** Bounded so custom provider lists cannot grow the status without limit; the rest is counted as "other". */
    public static final int MAX_SIGNALS = 32;
    private final LongSupplier clock;
    private final Map<FailureReason, Long> totals = new EnumMap<>(FailureReason.class);
    private final Map<FailureReason, Long> pending = new EnumMap<>(FailureReason.class);
    private final Map<String, Long> signals = new TreeMap<>(), families = new TreeMap<>();
    private long total, pendingCount, since;
    private ScheduledExecutorService timer;
    private Consumer<String> warning;

    public UncheckedVpnAdmissions() { this(System::currentTimeMillis); }
    UncheckedVpnAdmissions(LongSupplier clock) { this.clock = clock; since = clock.getAsLong(); }

    /** Called once at completion of a real native login, never for explain/cache/provider queries. */
    public void allowed(FailureReason reason) { allowed(reason, Collections.emptyList(), null); }
    public synchronized void allowed(FailureReason reason, Collection<String> hints, String family) {
        if (reason == null || reason == FailureReason.NONE) return;
        total++; pendingCount++;
        totals.merge(reason, 1L, Long::sum); pending.merge(reason, 1L, Long::sum);
        for (String hint : hints)
            signals.merge(signals.containsKey(hint) || signals.size() < MAX_SIGNALS - 1 ? hint : "other", 1L, Long::sum);
        if (family != null) families.merge(family, 1L, Long::sum);
    }

    /** Which voting sources stayed inconclusive and why, one key per source. Empty for exempt or conclusive results. */
    public static List<String> signals(VpnResult vpn, boolean exempt) {
        List<String> keys = new ArrayList<>();
        if (exempt || vpn == null || vpn.getStatus() != ProviderVote.Status.UNKNOWN) return keys;
        for (ProviderVote vote : vpn.getVotes()) {
            if (!vote.isVoting() || vote.getStatus() != ProviderVote.Status.UNKNOWN || vote.getReason() == FailureReason.NONE) continue;
            String source = vote.getProvider() == null ? "" : vote.getProvider().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
            if (source.isEmpty()) source = "unnamed";
            if (source.length() > 48) source = source.substring(0, 48);
            String kind = vote.getReason() != FailureReason.NO_EVIDENCE ? "failed"
                    : vote.getDetails() != null && Boolean.TRUE.equals(vote.getDetails().get(DetectionDetails.Type.HOSTING)) ? "hosting" : "no_evidence";
            String key = source + "." + kind;
            if (!keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    public static String family(String address) {
        return address == null || address.isEmpty() ? null : address.indexOf(':') >= 0 ? "ipv6" : "ipv4";
    }

    public static FailureReason unresolved(VpnResult vpn, boolean exempt) {
        if (exempt || vpn == null || vpn.getStatus() != ProviderVote.Status.UNKNOWN) return null;
        // A missing local hint must not hide a concrete quota refusal later in the same chain.
        for (ProviderVote vote : vpn.getVotes())
            if (vote.isVoting() && vote.getStatus() == ProviderVote.Status.UNKNOWN && vote.getReason() == FailureReason.BUDGET_EXHAUSTED)
                return FailureReason.BUDGET_EXHAUSTED;
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
        return new Snapshot(total, pendingCount, Math.max(0, clock.getAsLong() - since) / 1000, totals, pending, signals, families);
    }
    public static final class Snapshot {
        public final long total, sinceSummary, windowSeconds;
        public final Map<FailureReason, Long> reasons, windowReasons;
        public final Map<String, Long> signals, families;
        private Snapshot(long total, long pending, long seconds, Map<FailureReason, Long> reasons, Map<FailureReason, Long> windowReasons,
                         Map<String, Long> signals, Map<String, Long> families) {
            this.total = total; sinceSummary = pending; windowSeconds = seconds;
            this.reasons = Collections.unmodifiableMap(new EnumMap<>(reasons));
            this.windowReasons = Collections.unmodifiableMap(new EnumMap<>(windowReasons));
            this.signals = Collections.unmodifiableMap(new TreeMap<>(signals));
            this.families = Collections.unmodifiableMap(new TreeMap<>(families));
        }
        public String describe() {
            return "VPN uncheckedAllowed=" + total + " sinceSummary=" + sinceSummary + " windowSeconds=" + windowSeconds
                    + " reasons=" + reasons + " signals=" + signals + " families=" + families
                    + " (CG allowed without a conclusive VPN result; exemptions and denies excluded)";
        }
    }
    @Override public void close() {
        ScheduledExecutorService previous;
        synchronized (this) { previous = timer; timer = null; }
        if (previous != null) previous.shutdownNow();
        summarize();
    }
}
