package fixture;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.*;

/** Fixture-only grants to synthetic users. No production permissions or accounts. */
public final class NoticeVelocityFixture {
    @Inject public NoticeVelocityFixture(ProxyServer proxy) { }
    @Subscribe public void ready(ProxyInitializeEvent event) { System.out.println("NOTICE_FIXTURE_READY"); }
    @Subscribe public void permissions(PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player)) return;
        Player player = (Player) event.getSubject();
        event.setProvider(subject -> permission -> permission.equals("connectionguard.command.cloud") && !player.getUsername().equals("CGRegular")
                ? Tristate.TRUE : Tristate.FALSE);
    }
    @Subscribe public void joined(ServerPostConnectEvent event) { System.out.println("NOTICE_JOIN " + event.getPlayer().getUsername()); }
}
