package com.github.gerolndnr.connectionguard.core.lookup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Logins from addresses no public lookup can judge: a backend behind a proxy without IP forwarding, LAN or tunnel
 * players. Lookup services answer these inconsistently (Blackbox lists every private address), so login checks skip
 * VPN and country lookups for them. Documentation ranges (RFC 5737/3849) stay public: fixtures and benchmarks use them.
 * Only counts per address class are kept, never the addresses. */
public final class LocalAddresses {
    public enum Kind { LOOPBACK, PRIVATE, SHARED, LINK_LOCAL, UNSPECIFIED }
    public static final long WARNING_MILLIS = TimeUnit.HOURS.toMillis(6);
    private final LongSupplier clock;
    private final Map<Kind, Long> totals = new EnumMap<>(Kind.class);
    private long total, nextWarning = Long.MIN_VALUE;

    public LocalAddresses() { this(System::currentTimeMillis); }
    LocalAddresses(LongSupplier clock) { this.clock = clock; }

    /** Expects a literal address (see Exemptions.normalize); anything else is treated as public. */
    public static Optional<Kind> classify(String literal) {
        InetAddress address;
        try { address = literal == null || !literal.matches("[0-9a-fA-F:.]+") ? null : InetAddress.getByName(literal); }
        catch (UnknownHostException invalid) { address = null; }
        if (address == null) return Optional.empty();
        if (address.isAnyLocalAddress()) return Optional.of(Kind.UNSPECIFIED);
        if (address.isLoopbackAddress()) return Optional.of(Kind.LOOPBACK);
        if (address.isLinkLocalAddress()) return Optional.of(Kind.LINK_LOCAL);
        if (address.isSiteLocalAddress()) return Optional.of(Kind.PRIVATE);
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xFF, second = bytes[1] & 0xFF;
        if (bytes.length == 4) {
            if (first == 0) return Optional.of(Kind.UNSPECIFIED);
            if (first == 100 && (second & 0xC0) == 64) return Optional.of(Kind.SHARED); // 100.64.0.0/10: CGNAT, Tailscale
            return Optional.empty();
        }
        return (first & 0xFE) == 0xFC ? Optional.of(Kind.PRIVATE) : Optional.empty(); // fc00::/7 unique local
    }

    /** Counts one login and returns a console warning at most every six hours, the first one immediately. */
    public synchronized Optional<String> seen(Kind kind) {
        total++; totals.merge(kind, 1L, Long::sum);
        long now = clock.getAsLong();
        if (nextWarning != Long.MIN_VALUE && now < nextWarning) return Optional.empty();
        nextWarning = now + WARNING_MILLIS;
        return Optional.of("Connection Guard skipped VPN and country checks for " + total + " logins from non-public addresses "
                + totals + ". Lookup services cannot judge these addresses. If this server runs behind Velocity or BungeeCord, "
                + "enable IP forwarding or install Connection Guard on the proxy; on a LAN server this is expected. Deny rules still apply.");
    }

    public synchronized long total() { return total; }
    public synchronized Map<Kind, Long> totals() { return Collections.unmodifiableMap(new EnumMap<>(totals)); }
    public synchronized String describe() {
        return "Local address logins=" + total + " " + totals + " (VPN and country checks skipped; no addresses kept)";
    }
}
