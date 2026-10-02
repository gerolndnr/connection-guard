package com.github.gerolndnr.connectionguard.core.local;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkIndexTest {
    @Test void adjacentAndOverlappingNetworksMergeWithoutCrossingARealGap() {
        NetworkIndex index = new NetworkIndex(Arrays.asList("192.0.2.0/25", "192.0.2.128/25", "192.0.2.1/32", "192.0.4.0/24"));
        assertEquals(2, index.getIpv4Intervals()); assertEquals(4, index.getRecords());
        assertTrue(index.contains("192.0.2.255")); assertFalse(index.contains("192.0.3.0"));
        assertTrue(index.contains("192.0.4.0")); assertFalse(index.contains("192.0.5.0"));
    }
    @Test void mappedIpv6UsesIpv4WhileNativeFamiliesStaySeparate() {
        NetworkIndex index = new NetworkIndex(Arrays.asList("::ffff:192.0.2.0/120", "2001:db8::/32"));
        assertTrue(index.contains("::ffff:192.0.2.99")); assertTrue(index.contains("192.0.2.99"));
        assertTrue(index.contains("2001:0db8:ffff::1")); assertFalse(index.contains("2001:db9::1"));
        assertEquals(1, index.getIpv4Intervals()); assertEquals(1, index.getIpv6Intervals());
    }
    @Test void broadIpv6NetworksAreIndexedWithoutExpandingTheirAddresses() {
        NetworkIndex index = new NetworkIndex(Collections.singletonList("::/0"));
        assertTrue(index.contains("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
        assertFalse(index.contains("255.255.255.255")); assertEquals(1, index.getIpv6Intervals());
    }
    @Test void literalOnlyValidationAndRecordBoundsApplyBeforeActivation() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkIndex(Collections.nCopies(NetworkIndex.MAX_RECORDS+1, "192.0.2.1")));
        for (String invalid : new String[]{"example.com/24", "192.0.2.0/33", "2001:db8::/129", "::ffff:192.0.2.0/95"})
            assertThrows(IllegalArgumentException.class, () -> new NetworkIndex(Collections.singletonList(invalid)));
        assertThrows(IllegalArgumentException.class, () -> new NetworkIndex(Collections.singletonList("192.0.2.0/24")).contains("example.com"));
    }
    @Test void emptyListContainsNoEvidenceAndDoesNotInventFamilyCoverage() {
        NetworkIndex index = new NetworkIndex(Collections.emptyList());
        assertFalse(index.contains("192.0.2.1")); assertFalse(index.contains("2001:db8::1"));
        assertEquals(0, index.getIpv4Intervals()); assertEquals(0, index.getIpv6Intervals());
    }
}
