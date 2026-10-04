// SPDX-License-Identifier: MIT
package fixture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual backend events only. No access to the challenge, completion setters or account authority. */
public final class CGChallengeBackendFixture extends JavaPlugin implements Listener {
    private final Map<String, UUID> present = new ConcurrentHashMap<>();
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @EventHandler(priority=EventPriority.MONITOR) public void joined(PlayerJoinEvent event) {
        String name = event.getPlayer().getName();
        UUID uuid = event.getPlayer().getUniqueId();
        present.put(name, uuid);
        getLogger().info("CHALLENGE_BACKEND_JOIN name=" + name + " uuid=" + uuid);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        present.remove(event.getPlayer().getName(), event.getPlayer().getUniqueId());
        getLogger().info("CHALLENGE_BACKEND_QUIT name=" + event.getPlayer().getName());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 1 || !args[0].matches("[A-Za-z0-9_]{1,16}")) return true;
        UUID uuid = present.get(args[0]);
        getLogger().info("CHALLENGE_BACKEND_PRESENT name=" + args[0] + " present=" + (uuid != null) + " uuid=" + uuid);
        return true;
    }
}
