package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.cloud.*;
import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import java.util.concurrent.TimeUnit;

public final class CloudDashboardNoticeListener {
    @Subscribe public void joined(ServerPostConnectEvent event) {
        if (CloudSync.isRunning() && event.getPlayer().hasPermission(CloudLinkNotice.PERMISSION)) later(event.getPlayer(), 0);
    }
    private static void later(Player player, int retry) {
        ConnectionGuardVelocityPlugin plugin = ConnectionGuardVelocityPlugin.getInstance();
        plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
            if (!player.isActive() || !player.hasPermission(CloudLinkNotice.PERMISSION)) return;
            if (CloudSync.awaitingLinkState() && retry < 15) { later(player, retry + 1); return; }
            CloudSync.takeJoinNotice(player.getUniqueId(), true, false).ifPresent(notice -> {
                player.sendMessage(Component.text("[Connection Guard] ", NamedTextColor.AQUA).append(Component.text(notice.description, NamedTextColor.WHITE)));
                player.sendMessage(Component.text("[ " + notice.action + " ]", NamedTextColor.GREEN, TextDecoration.BOLD, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(notice.url)).hoverEvent(HoverEvent.showText(Component.text(notice.hover))));
                player.sendMessage(Component.text(notice.help, NamedTextColor.GRAY));
            });
        }).delay(2, TimeUnit.SECONDS).schedule();
    }
}
