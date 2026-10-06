package fixture;

import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Offline synthetic loopback identities only. Never install on a real server. */
public final class NoticeBackendFixture extends JavaPlugin implements Listener {
    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); }
    @EventHandler(priority = EventPriority.LOWEST) public void permission(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getName().equals("CGOperator")) player.setOp(true);
        else if (!player.getName().equals("CGRegular")) player.addAttachment(this, "connectionguard.command.cloud", true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        getLogger().info("NOTICE_JOIN " + player.getName());
        long start = System.nanoTime();
        ConnectionGuardSpigotPlugin.getInstance().tasks().entityLater(player, 40, () -> {
            try {
                boolean owned = (Boolean) Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class).invoke(null, player);
                if (!owned) throw new IllegalStateException("Wrong entity region.");
                getLogger().info("NOTICE_DELAY " + player.getName() + " ms=" + (System.nanoTime() - start) / 1000000);
            } catch (ReflectiveOperationException invalid) { throw new IllegalStateException(invalid); }
        });
    }
}
