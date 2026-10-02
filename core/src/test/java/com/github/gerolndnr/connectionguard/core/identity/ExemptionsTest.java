package com.github.gerolndnr.connectionguard.core.identity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExemptionsTest {
    @Test void aClaimedNameCannotExemptAConnection() {
        assertFalse(Exemptions.matches(Arrays.asList("Admin", "example.com"), "192.0.2.1", null, false));
    }
    @Test void onlyTrustedUuidsAreMatched() {
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertFalse(Exemptions.matches(Collections.singletonList(uuid.toString()), "192.0.2.1", uuid, false));
        assertTrue(Exemptions.matches(Collections.singletonList(uuid.toString()), "192.0.2.1", uuid, true));
        assertFalse(Exemptions.matches(Collections.singletonList(uuid.toString()), "192.0.2.1", null, true));
    }
    @Test void mappedIpv6MatchesIpv4() {
        assertTrue(Exemptions.matches(Collections.singletonList("192.0.2.1"), "::ffff:192.0.2.1", null, false));
    }
    @Test void ambiguousAndNonLiteralAddressesAreRejected() {
        for (String address : Arrays.asList("127.1", "1.2.3.999", "0177.0.0.1", "example.com", "::1%lo0", "1.2.3.4?key=x")) {
            assertThrows(IllegalArgumentException.class, () -> Exemptions.normalize(address));
        }
    }
}
