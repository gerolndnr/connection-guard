package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.bungee.ConnectionGuardBungeePlugin;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    @EventHandler
    public void onPreLogin(PreLoginEvent preLoginEvent) {
        preLoginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String ipAddress = preLoginEvent.getConnection().getAddress().getAddress().getHostAddress();

        CompletableFuture<VpnResult> vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);

        ConnectionGuardBungeePlugin.getInstance().getProxy().getScheduler().runAsync(ConnectionGuardBungeePlugin.getInstance(), new Runnable() {
            @Override
            public void run() {
                VpnResult vpnResult = vpnResultFuture.join();
                Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();

                if (vpnResult.isVpn()) {
                    preLoginEvent.setCancelled(true);
                    preLoginEvent.setCancelReason(TextComponent.fromLegacyText("You are using a vpn."));
                }
                preLoginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            }
        });
    }
}
