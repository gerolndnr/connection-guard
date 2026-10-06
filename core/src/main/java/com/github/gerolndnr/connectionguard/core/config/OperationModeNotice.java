package com.github.gerolndnr.connectionguard.core.config;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.logging.Logger;

/** A durable one-time notice, without editing an operator's config or selected mode. */
public final class OperationModeNotice {
    private OperationModeNotice() { }
    public static void show(Path directory, boolean existingInstallation, boolean observe, Logger logger) {
        Path marker = directory.resolve("operation-mode-v052.notice");
        try {
            Files.createDirectories(directory);
            Files.write(marker, (observe ? "OBSERVE\n" : "ENFORCE\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException alreadyShown) { return; }
        catch (IOException unavailable) { logger.warning("Could not persist the operation-mode notice; check plugin directory permissions."); return; }
        logger.warning(existingInstallation ? "Connection Guard upgrade: your " + (observe ? "OBSERVE" : "ENFORCE")
                + " mode is unchanged. New installations use ENFORCE. ProxyCheck/zowi hosting-only facts are review evidence; Blackbox listing may also cover hosting/cloud. Inspect /cg doctor."
                : "Connection Guard new installation: ENFORCE blocks VPN/proxy/Tor evidence and Blackbox aggregate listings (including hosting/cloud). ProxyCheck/zowi hosting-only facts are reviewed. Staff can grant an exception with /cg allow; inspect /cg doctor.");
    }
}
