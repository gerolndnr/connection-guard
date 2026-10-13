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
        logger.warning("Connection Guard upgrade: your configured providers and order are unchanged; your keys are retained. The 0.6.1 new-install recommendation is local Tor/Intel -> zowi -> Blackbox (fresh Intel HOSTING confirmation required) -> IPQuery. "
                + "ProxyCheck remains an explicit late fallback, with your own key; ip-check.net and IP-API stay opt-in. "
                + "To adopt it, these services receive player IPs; review docs/PROVIDERS.md and your server privacy information before enabling any new recipient, then consciously edit the existing provider selections/order and /cg reload. "
                + "zowi is operated by the developer of FoxGate; its conservative local cap remains 60/minute, not a promise of upstream allowance. No configuration has been rewritten.");
    }
}
