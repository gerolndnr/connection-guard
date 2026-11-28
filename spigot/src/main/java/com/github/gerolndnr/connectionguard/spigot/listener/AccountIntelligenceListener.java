package com.github.gerolndnr.connectionguard.spigot.listener;

import com.github.gerolndnr.connectionguard.core.account.AccountIntelligence;
import com.github.gerolndnr.connectionguard.spigot.PlatformTasks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;

/**
 * Account Intelligence after an admitted login: returns at once, any staff warning arrives later. Never denies.
 * Behind a proxy the backend sees the forwarded premium UUID (version 4); offline-mode UUIDs (version 3) are skipped.
 */
public final class AccountIntelligenceListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        AccountIntelligence.joined(player.getUniqueId(), player.getName(), true);
    }
    public static void install(PlatformTasks tasks) {
        AccountIntelligence.setNotifier((text, permission) -> {
            String message = ChatColor.translateAlternateColorCodes('&', text);
            tasks.global(() -> {
                for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers()))
                    tasks.entity(player, () -> { if (player.hasPermission(permission)) player.sendMessage(message); });
            });
        });
    }
}
