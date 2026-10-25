package com.github.gerolndnr.connectionguard.core.cloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CloudSignalDefaultsTest {
    private CloudSignalDefaults load(Path dir) {
        CloudSignalDefaults state = new CloudSignalDefaults(dir, Logger.getLogger("test")); state.load(); return state;
    }
    @Test void oldLinksStayOffAndNewLinksSurviveRestart(@TempDir Path dir) {
        CloudSignalDefaults state = load(dir); assertFalse(state.enabled());
        state.observe(true, false, false); assertFalse(state.enabled());
        state = load(dir); state.observe(true, false, false); assertFalse(state.enabled());
        state.observe(true, false, true); assertTrue(state.enabled());
        assertTrue(load(dir).enabled());
    }
    @Test void oldUnlinkedIdentitiesAndNewTokenInstallsCanDefaultOn(@TempDir Path dir) {
        CloudSignalDefaults state = load(dir.resolve("unlinked"));
        state.observe(false, false, false); assertTrue(state.enabled());
        state = load(dir.resolve("token")); state.observe(true, true, false); assertTrue(state.enabled());
    }
    @Test void corruptOrUnwritableStateNeverEnablesContribution(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("signals-link-default-v1"), "invalid".getBytes(StandardCharsets.US_ASCII));
        CloudSignalDefaults state = load(dir); state.observe(true, false, false); assertFalse(state.enabled());
        Path blocked = dir.resolve("not-directory"); Files.write(blocked, new byte[0]);
        state = load(blocked); state.observe(true, true, true); assertFalse(state.enabled());
    }
}
