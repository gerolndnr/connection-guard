package com.github.gerolndnr.connectionguard.core.lookup;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Conservative external-call reservations shared by VPN/Geo; no I/O or identity storage. */
public final class ApiLookupBudget {
    private final LongSupplier clock;
    private LocalDate day;
    private long reserved, denied, nextAlert;
    private int limit;
    public ApiLookupBudget() { this(System::currentTimeMillis); }
    ApiLookupBudget(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); roll(clock.getAsLong()); }
    public synchronized void configure(int dailyBudget) {
        if (dailyBudget < 0) throw new IllegalArgumentException("Daily API budget must be nonnegative.");
        roll(clock.getAsLong()); limit = dailyBudget;
    }
    /** Reserve both limits atomically. A source circuit/quota failure consumes no global reservation. */
    public synchronized FailureReason reserve(ProviderHealth source) {
        long now = clock.getAsLong(); roll(now);
        if (limit > 0 && reserved >= limit) { if (denied != Long.MAX_VALUE) denied++; return FailureReason.BUDGET_EXHAUSTED; }
        FailureReason reason = source.reserve(now);
        if (reason == FailureReason.NONE && reserved != Long.MAX_VALUE) reserved++;
        return reason;
    }
    private void roll(long now) {
        LocalDate current = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        // Clock rollback cannot replenish a consumed quota.
        if (day == null || current.isAfter(day)) { day = current; reserved = 0; denied = 0; nextAlert = 0; }
    }
    public synchronized boolean claimAlert() {
        long now = clock.getAsLong(); roll(now);
        if (limit == 0 || reserved < limit || now < nextAlert) return false;
        nextAlert = now + 30_000; return true;
    }
    public synchronized View snapshot() { roll(clock.getAsLong()); return new View(day, reserved, denied, limit); }
    public synchronized void reset() { day = null; roll(clock.getAsLong()); limit = 0; }
    public static final class View {
        public final LocalDate day;
        public final long reserved, denied;
        public final int limit;
        private View(LocalDate day, long reserved, long denied, int limit) { this.day=day; this.reserved=reserved; this.denied=denied; this.limit=limit; }
        public String describe() { return "API daily reservations="+reserved+" limit="+limit+" deniedReservations="+denied+" day="+day
                +" UTC; local process estimate, 0=unlimited; not account quota or confirmed HTTP count"; }
    }
}
