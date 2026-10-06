package com.github.gerolndnr.connectionguard.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationModeNoticeTest {
    @TempDir Path directory;
    @Test void existingObserveFileIsUnchangedAndNoticeOccursOnce() throws Exception {
        byte[] original = "operation:\n  mode: OBSERVE\n".getBytes(StandardCharsets.UTF_8);
        Path config = directory.resolve("config.yml"); Files.write(config, original);
        List<String> messages = new ArrayList<>(); Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() { public void publish(LogRecord r) { messages.add(r.getMessage()); } public void flush() { } public void close() { } });
        OperationModeNotice.show(directory, true, true, logger); OperationModeNotice.show(directory, true, true, logger);
        assertArrayEquals(original, Files.readAllBytes(config)); assertEquals(1, messages.size()); assertTrue(messages.get(0).contains("OBSERVE mode is unchanged"));
        assertTrue(GuardSettings.read(key -> key.equals("operation.mode") ? "OBSERVE" : null, Collections.emptyList()).observe);
        assertFalse(GuardSettings.defaults().observe); // legacy missing-mode behavior
    }
}
