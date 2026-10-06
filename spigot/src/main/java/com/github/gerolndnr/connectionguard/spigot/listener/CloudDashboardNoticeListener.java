package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cloud.*;
import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;

/** Optional setup notice after login has completed; no HTTP, lookup or disk access on the entity thread. */
public final class CloudDashboardNoticeListener implements Listener {
    @EventHandler public void joined(PlayerJoinEvent event) {
        if (CloudSync.isRunning() && (event.getPlayer().isOp() || event.getPlayer().hasPermission(CloudLinkNotice.PERMISSION))) later(event.getPlayer(), 0);
    }
    private static void later(Player player, int retry) {
        ConnectionGuardSpigotPlugin plugin = ConnectionGuardSpigotPlugin.getInstance();
        plugin.tasks().entityLater(player, 40, () -> {
            boolean authorized = player.isOp() || player.hasPermission(CloudLinkNotice.PERMISSION);
            if (!authorized || proxyBackend()) return;
            if (CloudSync.awaitingLinkState() && retry < 15) { later(player, retry + 1); return; }
            CloudSync.takeJoinNotice(player.getUniqueId(), authorized, false).ifPresent(notice -> {
                player.sendMessage(ChatColor.AQUA + "[Connection Guard] " + ChatColor.WHITE + notice.description);
                TextComponent link = new TextComponent("[ " + notice.action + " ]");
                link.setColor(ChatColor.GREEN); link.setBold(true); link.setUnderlined(true);
                link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, notice.url));
                link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{new TextComponent(notice.hover)}));
                player.spigot().sendMessage(link);
                player.sendMessage(ChatColor.GRAY + notice.help);
            });
        });
    }
    private static boolean proxyBackend() {
        if (ConnectionGuard.getSettings().trustForwardedIdentity || ConnectionGuard.getSettings().nativePaperForwardingIdentity) return true;
        try {
            if (org.bukkit.Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false)) return true;
        } catch (RuntimeException unsupported) { /* Legacy implementations may not expose Spigot settings. */ }
        try {
            Object global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration").getMethod("get").invoke(null);
            Object proxies = global.getClass().getField("proxies").get(global);
            Object velocity = proxies.getClass().getField("velocity").get(proxies);
            return velocity.getClass().getField("enabled").getBoolean(velocity);
        } catch (ReflectiveOperationException | LinkageError absent) {
            try { return Class.forName("com.destroystokyo.paper.PaperConfig").getField("velocitySupport").getBoolean(null); }
            catch (ReflectiveOperationException | LinkageError legacy) { return false; }
        }
    }
}
