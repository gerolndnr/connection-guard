package fixture;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.event.EventHandler;

/** Fixture-only grants to synthetic users. No production permissions or accounts. */
public final class NoticeBungeeFixture extends Plugin implements Listener {
    @Override public void onEnable() { getProxy().getPluginManager().registerListener(this, this); System.out.println("BUNGEE_FIXTURE_READY"); }
    @EventHandler public void permissions(PermissionCheckEvent event) {
        if (event.getSender() instanceof ProxiedPlayer && event.getPermission().equals("connectionguard.command.cloud"))
            event.setHasPermission(!((ProxiedPlayer) event.getSender()).getName().equals("CGRegular"));
    }
    @EventHandler public void joined(ServerConnectedEvent event) { System.out.println("NOTICE_JOIN " + event.getPlayer().getName()); }
}
