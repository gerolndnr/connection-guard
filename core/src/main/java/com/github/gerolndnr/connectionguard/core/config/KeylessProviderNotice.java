package com.github.gerolndnr.connectionguard.core.config;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.logging.Logger;

/** One upgrade recommendation, never a configuration migration or additional recipient consent. */
public final class KeylessProviderNotice {
    private KeylessProviderNotice() { }
    public static void show(Path directory, boolean existingInstallation, Logger logger) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("keyless-providers-v052.notice"), new byte[]{'1', '\n'}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException shown) { return; }
        catch (IOException unavailable) { logger.warning("Could not persist the keyless-provider upgrade notice; check plugin directory permissions."); return; }
        if (!existingInstallation) return;
        logger.warning("Connection Guard upgrade: your configured providers and order are unchanged. The new-install recommendation is local Tor -> proxycheck -> blackbox -> ipcheck -> zowi -> ipquery -> ip-api. "
                + "To opt in, explicitly add provider.vpn.blackbox.enabled: true, provider.vpn.ipcheck.enabled: true and provider.vpn.zowi.enabled: true, then set provider.vpn-failover.order: [proxycheck, blackbox, ipcheck, zowi, ipquery, ip-api] "
                + "for the providers present in your config and /cg reload. These services receive player IPs; review docs/PROVIDERS.md and update your server privacy information first. Blackbox can also deny hosting/cloud listings.");
    }
}
