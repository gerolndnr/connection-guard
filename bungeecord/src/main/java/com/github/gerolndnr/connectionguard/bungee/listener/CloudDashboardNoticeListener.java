package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.core.cloud.*;
import com.github.gerolndnr.connectionguard.bungee.ConnectionGuardBungeePlugin;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.*;
import java.util.concurrent.TimeUnit;

public final class CloudDashboardNoticeListener implements Listener {
    @EventHandler public void joined(ServerConnectedEvent event) {
        if (CloudSync.isRunning() && event.getPlayer().hasPermission(CloudLinkNotice.PERMISSION)) later(event.getPlayer(), 0);
    }
    private static void later(ProxiedPlayer player, int retry) {
        ConnectionGuardBungeePlugin plugin = ConnectionGuardBungeePlugin.getInstance();
        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (!player.isConnected() || !player.hasPermission(CloudLinkNotice.PERMISSION)) return;
            if (CloudSync.awaitingLinkState() && retry < 15) { later(player, retry + 1); return; }
            CloudSync.takeJoinNotice(player.getUniqueId(), true, false).ifPresent(notice -> {
                TextComponent title = new TextComponent("[Connection Guard] " + notice.description); title.setColor(ChatColor.AQUA);
                TextComponent link = new TextComponent("[ " + notice.action + " ]"); link.setColor(ChatColor.GREEN); link.setBold(true); link.setUnderlined(true);
                link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, notice.url));
                link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{new TextComponent(notice.hover)}));
                TextComponent help = new TextComponent(notice.help); help.setColor(ChatColor.GRAY);
                player.sendMessage(title); player.sendMessage(link); player.sendMessage(help);
            });
        }, 2, TimeUnit.SECONDS);
    }
}
