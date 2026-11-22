package com.github.gerolndnr.connectionguard.core.lookup;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CompletionException;

/** Counts requests locally, not a claim about the provider account's remaining quota. */
public final class ProviderHealth {
    private int failures;
    private long pausedUntil;
    private long attempts;
    private long successes;
    private long dayRequests;
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private long minuteStart;
    private int minuteRequests;
    private int dailyBudget;
    private int minuteBudget;
    private String announcedSource;
    private FailureReason lastReason = FailureReason.NONE;
    private long lastAlert;
    private FailureReason alertedReason = FailureReason.NONE;
    private boolean probe;
    public synchronized boolean claimAlert() {
        long now = System.currentTimeMillis();
        if (now - lastAlert < 30000 && alertedReason == lastReason) return false;
        lastAlert = now; alertedReason = lastReason; return true;
    }
    public synchronized void resetFailures() { failures = 0; pausedUntil = 0; probe = false; lastReason = FailureReason.NONE; }
    public synchronized void budgets(int daily, int minute) { budgets(daily, minute, null); }
    /**
     * With an announced source ({@code minute-budget: auto}), the minute budget follows 80 % of the
     * per-minute limit that source last announced; {@code minute} applies until it announces one.
     */
    public synchronized void budgets(int daily, int minute, String announcedSource) {
        if (daily < 0 || minute < 0) throw new IllegalArgumentException("Provider budgets must be nonnegative.");
        dailyBudget = daily; minuteBudget = minute; this.announcedSource = announcedSource;
    }
    public synchronized int effectiveMinuteBudget() {
        int announced = com.github.gerolndnr.connectionguard.core.http.AnnouncedLimits.get(announcedSource);
        return announced > 0 ? Math.max(1, announced * 4 / 5) : minuteBudget;
    }
    private void rollDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) { day = today; dayRequests = 0; }
    }
    public synchronized FailureReason reserve(long now) {
        rollDay();
        if (now - minuteStart >= 60000) { minuteStart = now; minuteRequests = 0; }
        if (now < pausedUntil || probe) return FailureReason.CIRCUIT_OPEN;
        if ((dailyBudget > 0 && dayRequests >= dailyBudget) || (effectiveMinuteBudget() > 0 && minuteRequests >= effectiveMinuteBudget())) {
            lastReason = FailureReason.BUDGET_EXHAUSTED;
            return FailureReason.BUDGET_EXHAUSTED;
        }
        if (pausedUntil != 0) probe = true; // one half-open recovery request for this account
        attempts++; dayRequests++; minuteRequests++;
        return FailureReason.NONE;
    }
    public synchronized void record(FailureReason reason, Throwable error, LookupSettings settings) {
        probe = false;
        if ((reason == FailureReason.NONE || reason == FailureReason.NO_EVIDENCE || reason == FailureReason.STALE_DATA)
                && System.currentTimeMillis() < pausedUntil && (lastReason == FailureReason.TIMEOUT || lastReason == FailureReason.RATE_LIMIT || lastReason == FailureReason.BUDGET_EXHAUSTED)) { successes++; return; }
        lastReason = reason;
        if (reason == FailureReason.NONE || reason == FailureReason.NO_EVIDENCE || reason == FailureReason.STALE_DATA) { successes++; failures = 0; pausedUntil = 0; return; }
        if (reason == FailureReason.OVERLOADED || reason == FailureReason.CANCELLED) return;
        // Honor the operator's configured pause; a provider's explicit Retry-After is a lower bound.
        long pause = settings.circuitPauseMillis;
        while (error instanceof CompletionException) error = error.getCause();
        if (error instanceof LookupException) pause = Math.max(pause, ((LookupException) error).getRetryAfterMillis());
        // A single slow answer no longer pauses a source: under a join wave that left every later
        // login unchecked for the whole pause. Timeouts count toward lookup.circuit.failures like
        // other errors; explicit rate limits and used-up quotas still pause at once.
        if (reason == FailureReason.RATE_LIMIT || reason == FailureReason.BUDGET_EXHAUSTED || ++failures >= settings.circuitFailures) {
            pausedUntil = System.currentTimeMillis() + Math.min(86400000, pause);
        }
    }
    /** Immutable view for status reporting (cloud dashboard). Local estimates, like describe(). */
    public synchronized Snapshot snapshot() {
        rollDay();
        return new Snapshot(attempts, successes, dayRequests, dailyBudget, System.currentTimeMillis() < pausedUntil || probe, lastReason);
    }
    public static final class Snapshot {
        public final long attempts, successes, dailyUsed;
        public final int dailyBudget;
        public final boolean paused;
        public final FailureReason lastReason;
        Snapshot(long attempts, long successes, long dailyUsed, int dailyBudget, boolean paused, FailureReason lastReason) {
            this.attempts = attempts; this.successes = successes; this.dailyUsed = dailyUsed; this.dailyBudget = dailyBudget;
            this.paused = paused; this.lastReason = lastReason;
        }
    }
    public synchronized String describe() {
        rollDay();
        return "attempts=" + attempts + " successes=" + successes + " last=" + lastReason
                + " pauseMs=" + Math.max(0, pausedUntil - System.currentTimeMillis())
                + " localDailyUsed=" + dayRequests + " localDailyLimit=" + dailyBudget
                + " (local estimate; 0=unlimited; account quota not reported)";
    }
}
