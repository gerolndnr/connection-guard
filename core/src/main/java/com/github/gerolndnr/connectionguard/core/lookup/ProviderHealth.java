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
    private FailureReason lastReason = FailureReason.NONE;
    private long lastAlert;
    public synchronized boolean claimAlert() {
        long now = System.currentTimeMillis();
        if (now - lastAlert < 30000) return false;
        lastAlert = now; return true;
    }
    public synchronized void resetFailures() { failures = 0; pausedUntil = 0; lastReason = FailureReason.NONE; }
    public synchronized void budgets(int daily, int minute) {
        if (daily < 0 || minute < 0) throw new IllegalArgumentException("Provider budgets must be nonnegative.");
        dailyBudget = daily; minuteBudget = minute;
    }
    public synchronized FailureReason reserve(long now) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) { day = today; dayRequests = 0; }
        if (now - minuteStart >= 60000) { minuteStart = now; minuteRequests = 0; }
        if (now < pausedUntil) return FailureReason.CIRCUIT_OPEN;
        if ((dailyBudget > 0 && dayRequests >= dailyBudget) || (minuteBudget > 0 && minuteRequests >= minuteBudget)) {
            return FailureReason.BUDGET_EXHAUSTED;
        }
        attempts++; dayRequests++; minuteRequests++;
        return FailureReason.NONE;
    }
    public synchronized void record(FailureReason reason, Throwable error, LookupSettings settings) {
        lastReason = reason;
        if (reason == FailureReason.NONE) { successes++; failures = 0; pausedUntil = 0; return; }
        if (reason == FailureReason.OVERLOADED || reason == FailureReason.CANCELLED) return;
        long pause = settings.circuitPauseMillis;
        while (error instanceof CompletionException) error = error.getCause();
        if (error instanceof LookupException) pause = Math.max(pause, ((LookupException) error).getRetryAfterMillis());
        if (reason == FailureReason.RATE_LIMIT || ++failures >= settings.circuitFailures) {
            pausedUntil = System.currentTimeMillis() + Math.min(3600000, pause);
        }
    }
    public synchronized String describe() {
        return "attempts=" + attempts + " successes=" + successes + " last=" + lastReason
                + " pauseMs=" + Math.max(0, pausedUntil - System.currentTimeMillis())
                + " localDailyUsed=" + dayRequests + " localDailyLimit=" + dailyBudget
                + " (local estimate; 0=unlimited; account quota not reported)";
    }
}
