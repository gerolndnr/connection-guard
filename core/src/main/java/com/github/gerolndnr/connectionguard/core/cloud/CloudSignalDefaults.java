package com.github.gerolndnr.connectionguard.core.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Local upgrade boundary: an already-linked identity never gains contribution just by upgrading. */
final class CloudSignalDefaults {
    private final Path file;
    private final Logger log;
    private boolean known, enabled;
    CloudSignalDefaults(Path directory, Logger log) {
        file = directory.resolve("signals-link-default-v1"); this.log = log;
    }
    synchronized void load() {
        try {
            byte[] bytes = CloudFiles.read(file, 16);
            if (bytes == null) return;
            String value = new String(bytes, StandardCharsets.US_ASCII);
            if (!value.equals("enabled") && !value.equals("disabled")) throw new IOException();
            enabled = value.equals("enabled"); known = true;
        } catch (IOException | RuntimeException invalid) { unavailable(); }
    }
    synchronized boolean enabled() { return known && enabled; }
    synchronized void observe(boolean claimed, boolean newIdentity, boolean newlyLinked) {
        if (known && !newlyLinked) return;
        boolean next = newlyLinked || newIdentity || !claimed;
        try {
            CloudFiles.write(file, (next ? "enabled" : "disabled").getBytes(StandardCharsets.US_ASCII));
            enabled = next; known = true;
        } catch (IOException | RuntimeException unavailable) { unavailable(); }
    }
    private void unavailable() {
        known = true; enabled = false;
        log.warning("Connection Guard network signal default stays off: local preference unavailable (values redacted). Set cloud.contribute-signals explicitly to choose.");
    }
}
