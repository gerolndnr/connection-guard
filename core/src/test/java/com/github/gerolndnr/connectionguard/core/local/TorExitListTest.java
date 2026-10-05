package com.github.gerolndnr.connectionguard.core.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TorExitListTest {
    @TempDir Path directory;
    private byte[] fixture(long fetched) {
        StringBuilder list = new StringBuilder("# fetched-at=" + fetched + "\n");
        for (int i = 1; i <= 100; i++) list.append("2001:db8::").append(Integer.toHexString(i)).append('\n');
        return list.toString().getBytes(StandardCharsets.UTF_8);
    }
    @Test void locallyLoadedAndStaleMembershipBlocksWithoutAnyNetwork() throws Exception {
        Files.write(directory.resolve("tor-exits.txt"), fixture(System.currentTimeMillis() - 172800000));
        try (TorExitList list = new TorExitList(directory, Logger.getAnonymousLogger())) {
            assertTrue(list.lookup("2001:db8:0:0:0:0:0:1").get().isVpn());
            assertTrue(list.describe().contains("stale=true"));
            assertFalse(list.lookup("192.0.2.7").isPresent());
        }
    }
    @Test void malformedOrTruncatedUpdatesCannotReplaceProtection() throws Exception {
        assertThrows(Exception.class, () -> TorExitList.parse("<html>error</html>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> TorExitList.parse("# fetched-at=1\n192.0.2.1\n".getBytes(StandardCharsets.UTF_8)));
        byte[] valid = fixture(System.currentTimeMillis()); assertEquals(100, TorExitList.parse(valid).addresses.size());
        Files.write(directory.resolve("tor-exits.txt"), "broken response".getBytes(StandardCharsets.UTF_8));
        try (TorExitList list = new TorExitList(directory, Logger.getAnonymousLogger())) { assertTrue(list.describe().matches(".*exits=[1-9][0-9]{2,}.*")); }
    }
}
