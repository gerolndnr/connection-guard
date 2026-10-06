package com.github.gerolndnr.connectionguard.core.config;

import java.nio.file.*;
import java.io.IOException;
import java.util.logging.Logger;

/** One recommendation on existing installs; never edits selections or starts a download. */
public final class IntelProviderNotice {
    private IntelProviderNotice() { }
    public static void show(Path directory,boolean existingInstallation,Logger logger){
        try {Files.createDirectories(directory);Files.write(directory.resolve("connectionguard-intel-v052.notice"),new byte[]{'1','\n'},StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
        catch(FileAlreadyExistsException shown){return;}
        catch(IOException unavailable){logger.warning("Could not persist Intel upgrade notice; check plugin directory permissions.");return;}
        if(!existingInstallation)return;
        logger.warning("Connection Guard Intel is available; your local source selection is unchanged. To opt in set provider.local.connectionguard-intel.enabled: true and /cg reload. The daily signed list fetch contacts https://intel.connectionguard.net/ without player IPs; review docs/LOCAL_DATA.md. Relay defaults to ALLOW, hosting remains review, stale data becomes UNKNOWN after 72h.");
    }
}
