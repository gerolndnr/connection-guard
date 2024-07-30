package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public class AsyncPlayerPreLoginListener implements Listener {
    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent preLoginEvent) {
        String ipAddress = preLoginEvent.getAddress().getHostAddress();

        VpnResult vpnResult = ConnectionGuard.getVpnResult(ipAddress).join();
        if (vpnResult.isVpn()) {
            preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "vpn");
        }
    }
}
