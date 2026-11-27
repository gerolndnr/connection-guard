package com.github.gerolndnr.connectionguard.core.config;

import java.nio.file.*;
import java.io.IOException;
import java.util.logging.Logger;

/** One recommendation on existing installs; never edits selections or starts a download. */
public final class IntelProviderNotice {
    private IntelProviderNotice() { }
    public static void show(Path directory,boolean existingInstallation,Logger logger){
        if(once(directory,"connectionguard-intel-v062-blocking.notice",logger)&&existingInstallation)
            logger.warning("Connection Guard Intel now refuses hosting and data-centre networks by default (provider.local.connectionguard-intel.hosting: BLOCK; REVIEW restores review only). A relay setting already in your config.yml is kept: relay: VPN also refuses Cloudflare WARP and iCloud Private Relay, which a banned player can switch on to come back.");
        if(!once(directory,"connectionguard-intel-v052.notice",logger)||!existingInstallation)return;
        logger.warning("Connection Guard Intel is available; your local source selection is unchanged. To opt in set provider.local.connectionguard-intel.enabled: true and /cg reload. The daily signed list fetch contacts https://intel.connectionguard.net/ without player IPs; review docs/LOCAL_DATA.md. Relay (WARP, Private Relay) and hosting networks count as VPN by default, stale data becomes UNKNOWN after 72h.");
    }
    private static boolean once(Path directory,String name,Logger logger){
        try {Files.createDirectories(directory);Files.write(directory.resolve(name),new byte[]{'1','\n'},StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);return true;}
        catch(FileAlreadyExistsException shown){return false;}
        catch(IOException unavailable){logger.warning("Could not persist Intel upgrade notice; check plugin directory permissions.");return false;}
    }
}
