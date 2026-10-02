package com.github.gerolndnr.connectionguard.core.admission;

import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.rules.IpNetwork;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded in-memory counters. No background threads, persisted identities or active-counter eviction. */
public final class AdmissionController {
    private final AdmissionSettings settings;
    private final LongSupplier clock;
    private final Map<String, Counter> counters = new HashMap<>();
    private long globalStart, cleanupAt, globalBlockedAt, alertAt;
    private boolean initialized, globalBlocked, alerted;
    private int globalCount;
    private long admitted, skipped, bypassed, alerts;
    public AdmissionController(AdmissionSettings settings) { this(settings, System::nanoTime); }
    public AdmissionController(AdmissionSettings settings, LongSupplier monotonicNanos) {
        this.settings = java.util.Objects.requireNonNull(settings);
        this.clock = java.util.Objects.requireNonNull(monotonicNanos);
    }
    public synchronized LoginAdmission admit(String literal, boolean lookupNeeded, boolean observe) {
        if (!settings.enabled || !lookupNeeded) {
            bypassed = increment(bypassed);
            return new LoginAdmission(LoginAdmission.Reason.NONE, 0, false, false);
        }
        String ip = Exemptions.normalize(literal);
        long now = clock.getAsLong();
        if (!initialized) { initialized = true; globalStart = cleanupAt = now; }
        if (globalBlocked) {
            long remaining = remaining(now, globalBlockedAt, settings.cooldownMillis);
            if (remaining > 0) return refuse(LoginAdmission.Reason.GLOBAL_LIMIT, remaining, now, observe);
            // Start a fresh global window after the pause, even if the pause is shorter than the old window.
            globalBlocked = false; globalCount = 0; globalStart = now;
        }
        if (elapsed(now, globalStart, settings.windowMillis)) { globalStart = now; globalCount = 0; }
        if (elapsed(now, cleanupAt, settings.windowMillis)) { cleanup(now); cleanupAt = now; }
        // Rejected local attempts never increase global pressure or another subnet's counters.
        if (settings.ipLimit > 0) {
            Counter ipCounter = counter("ip:" + ip, now);
            if (ipCounter == null) return refuse(LoginAdmission.Reason.TRACKING_CAPACITY, capacityRetry(now), now, observe);
            long wait = ipCounter.attempt(now, settings.ipLimit);
            if (wait > 0) return refuse(LoginAdmission.Reason.IP_LIMIT, wait, now, observe);
        }
        if (settings.subnetLimit > 0) {
            String network = IpNetwork.parse(ip + "/" + (ip.indexOf(':') < 0 ? settings.ipv4Prefix : settings.ipv6Prefix)).toString();
            Counter subnetCounter = counter("subnet:" + network, now);
            if (subnetCounter == null) return refuse(LoginAdmission.Reason.TRACKING_CAPACITY, capacityRetry(now), now, observe);
            long wait = subnetCounter.attempt(now, settings.subnetLimit);
            if (wait > 0) return refuse(LoginAdmission.Reason.SUBNET_LIMIT, wait, now, observe);
        }
        if (settings.globalLimit > 0 && ++globalCount > settings.globalLimit) {
            globalBlocked = true; globalBlockedAt = now;
            return refuse(LoginAdmission.Reason.GLOBAL_LIMIT, settings.cooldownMillis, now, observe);
        }
        admitted = increment(admitted);
        return new LoginAdmission(LoginAdmission.Reason.NONE, 0, false, false);
    }
    private Counter counter(String key, long now) {
        Counter counter = counters.get(key);
        if (counter != null) return counter;
        if (counters.size() >= settings.trackingCapacity) cleanup(now);
        if (counters.size() >= settings.trackingCapacity) return null;
        counter = new Counter(now); counters.put(key, counter); return counter;
    }
    private void cleanup(long now) { counters.values().removeIf(counter -> counter.expired(now)); }
    private long capacityRetry(long now) {
        long wait = Long.MAX_VALUE;
        for (Counter counter : counters.values()) wait = Math.min(wait, counter.retry(now));
        return Math.max(1, wait);
    }
    private LoginAdmission refuse(LoginAdmission.Reason reason, long wait, long now, boolean observe) {
        skipped = increment(skipped);
        boolean alert = !alerted || elapsed(now, alertAt, settings.alarmCooldownMillis);
        if (alert) { alerted = true; alertAt = now; alerts = increment(alerts); }
        return new LoginAdmission(reason, wait, settings.denyConnections && !observe, alert);
    }
    /** Address-free status. Inspection neither spends admission nor starts a global pause. */
    public synchronized String describe() {
        long retry = globalBlocked ? remaining(clock.getAsLong(), globalBlockedAt, settings.cooldownMillis) : 0;
        return "Admission enabled=" + settings.enabled + " state=" + (retry > 0 ? "GLOBAL_PAUSE" : "NORMAL")
                + " globalRetryMs=" + retry + " denyConnections=" + settings.denyConnections
                + " admitted=" + admitted + " skipped=" + skipped + " bypassed=" + bypassed
                + " tracked=" + counters.size() + "/" + settings.trackingCapacity + " alerts=" + alerts;
    }
    public synchronized int tracked() { return counters.size(); }
    private static long increment(long count) { return count == Long.MAX_VALUE ? count : count + 1; }
    private static boolean elapsed(long now, long start, int millis) { return now - start >= millis * 1000000L; }
    private static long remaining(long now, long start, int millis) {
        long nanos = millis * 1000000L - (now - start);
        return nanos <= 0 ? 0 : (nanos + 999999L) / 1000000L;
    }
    private final class Counter {
        private long start, blockedAt;
        private int count;
        private boolean blocked;
        private Counter(long now) { start = now; }
        private long attempt(long now, int limit) {
            if (blocked) {
                long wait = remaining(now, blockedAt, settings.cooldownMillis);
                if (wait > 0) return wait;
                blocked = false; start = now; count = 0;
            }
            if (elapsed(now, start, settings.windowMillis)) { start = now; count = 0; }
            if (++count <= limit) return 0;
            blocked = true; blockedAt = now; return settings.cooldownMillis;
        }
        private boolean expired(long now) { return blocked ? elapsed(now, blockedAt, settings.cooldownMillis) : elapsed(now, start, settings.windowMillis); }
        private long retry(long now) { return remaining(now, blocked ? blockedAt : start, blocked ? settings.cooldownMillis : settings.windowMillis); }
    }
}
