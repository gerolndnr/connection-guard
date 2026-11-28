package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.account.AccountIntelligence;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Account Intelligence after an admitted login: returns at once, any staff warning arrives later. Never denies. */
public final class AccountIntelligenceListener {
    @Subscribe public void joined(PostLoginEvent event) {
        Player player = event.getPlayer();
        AccountIntelligence.joined(player.getUniqueId(), player.getUsername(), player.isOnlineMode());
    }
    public static void install(ProxyServer proxy) {
        AccountIntelligence.setNotifier((text, permission) -> {
            Component message = LegacyComponentSerializer.legacyAmpersand().deserialize(text);
            for (Player player : proxy.getAllPlayers()) if (player.hasPermission(permission)) player.sendMessage(message);
        });
    }
}
