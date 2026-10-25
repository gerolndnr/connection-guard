package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.lookup.LocalAddresses.Kind;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAddressesTest {
    private static Kind kind(String ip) { return LocalAddresses.classify(ip).orElse(null); }

    @Test void classifiesEveryNonPublicRangeAtItsEdges() {
        assertEquals(Kind.LOOPBACK, kind("127.0.0.1")); assertEquals(Kind.LOOPBACK, kind("127.255.255.254")); assertEquals(Kind.LOOPBACK, kind("::1"));
        for (String ip : Arrays.asList("10.0.0.5", "172.16.0.1", "172.18.0.2", "172.31.255.255", "192.168.1.20", "fc00::1", "fd12:3456::7"))
            assertEquals(Kind.PRIVATE, kind(ip), ip);
        assertEquals(Kind.SHARED, kind("100.64.0.10")); assertEquals(Kind.SHARED, kind("100.127.255.255"));
        assertEquals(Kind.LINK_LOCAL, kind("169.254.10.1")); assertEquals(Kind.LINK_LOCAL, kind("fe80::1"));
        assertEquals(Kind.UNSPECIFIED, kind("0.0.0.0")); assertEquals(Kind.UNSPECIFIED, kind("0.1.2.3")); assertEquals(Kind.UNSPECIFIED, kind("::"));
        assertEquals(Kind.PRIVATE, kind("::ffff:10.0.0.5"), "IPv4-mapped addresses are judged as IPv4");
    }

    @Test void publicAndDocumentationAddressesStayPublic() {
        for (String ip : Arrays.asList("172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "11.0.0.1", "192.169.0.1",
                "8.8.8.8", "192.0.2.41", "198.51.100.7", "203.0.113.10", "2001:db8::1", "2a00:1450::1", "fbff::1", "2001::1"))
            assertNull(kind(ip), ip);
        assertNull(kind(null)); assertNull(kind("")); assertNull(kind("localhost"), "Never resolves names");
    }

    @Test void warnsOnTheFirstLoginThenAtMostEverySixHoursWithCountsButNoAddresses() {
        AtomicLong time = new AtomicLong(1_000); LocalAddresses tracker = new LocalAddresses(time::get);
        Optional<String> first = tracker.seen(Kind.PRIVATE);
        assertTrue(first.isPresent()); assertTrue(first.get().contains("1 logins")); assertTrue(first.get().contains("IP forwarding"));
        for (int i = 0; i < 40; i++) assertFalse(tracker.seen(Kind.PRIVATE).isPresent());
        time.addAndGet(LocalAddresses.WARNING_MILLIS - 1); assertFalse(tracker.seen(Kind.LOOPBACK).isPresent());
        time.addAndGet(1); Optional<String> next = tracker.seen(Kind.PRIVATE);
        assertTrue(next.isPresent()); assertTrue(next.get().contains("43 logins")); assertTrue(next.get().contains("PRIVATE=42"));
        assertEquals(43, tracker.total()); assertEquals(1L, tracker.totals().get(Kind.LOOPBACK));
        assertFalse(tracker.describe().matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), "No address in any output");
    }
}
