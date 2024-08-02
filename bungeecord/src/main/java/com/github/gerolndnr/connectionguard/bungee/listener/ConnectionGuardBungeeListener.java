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

        ConnectionGuard.getLogger().info("socket address: " + preLoginEvent.getConnection().getSocketAddress().toString());

        String ipAddress = preLoginEvent.getConnection().getAddress().getAddress().getHostAddress();

        ConnectionGuard.getLogger().info("ip: " + ipAddress);

        CompletableFuture<VpnResult> vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);

        ConnectionGuardBungeePlugin.getInstance().getProxy().getScheduler().runAsync(ConnectionGuardBungeePlugin.getInstance(), new Runnable() {
            @Override
            public void run() {
                ConnectionGuard.getLogger().info("in completable future");
                VpnResult vpnResult = vpnResultFuture.join();
                Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();

                if (vpnResult.isVpn()) {
                    ConnectionGuard.getLogger().info("is vpn");
                    preLoginEvent.setCancelled(true);
                    preLoginEvent.setCancelReason(TextComponent.fromLegacyText("You are using a vpn."));
                }
                ConnectionGuard.getLogger().info("reached end");
                preLoginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            }
        });
    }
}
