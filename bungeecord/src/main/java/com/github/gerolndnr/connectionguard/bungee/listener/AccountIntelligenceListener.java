package com.github.gerolndnr.connectionguard.bungee.listener;

import com.github.gerolndnr.connectionguard.core.account.AccountIntelligence;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/** Account Intelligence after an admitted login: returns at once, any staff warning arrives later. Never denies. */
public final class AccountIntelligenceListener implements Listener {
    @EventHandler public void joined(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        AccountIntelligence.joined(player.getUniqueId(), player.getName(), player.getPendingConnection().isOnlineMode());
    }
    public static void install(ProxyServer proxy) {
        AccountIntelligence.setNotifier((text, permission) -> {
            String message = ChatColor.translateAlternateColorCodes('&', text);
            for (ProxiedPlayer player : proxy.getPlayers()) if (player.hasPermission(permission)) player.sendMessage(TextComponent.fromLegacyText(message));
        });
    }
}
