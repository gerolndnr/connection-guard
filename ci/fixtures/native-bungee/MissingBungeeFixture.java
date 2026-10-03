package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.identity.FloodgateIdentity;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.event.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** No native SDK on compile/runtime classpath; no stand-in authority. */
public final class MissingBungeeFixture extends Plugin implements Listener {
    private ProviderRegistration provider; private ObserverRegistration observer;
    @Override public void onEnable() {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-bungee-fixture", "1", String.join("", Collections.nCopies(64, "f")), true),
                ip -> CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty())));
        observer = ConnectionGuardApi.registerDecisionObserver("native-bungee-observer", o ->
                System.out.println("BUNGEE_DECISION outcome=" + o.getOutcome() + " reason=" + o.getReason() + " identity=" + o.getIdentityTrust() + " vpn=" + o.getVpnCheck()));
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("fixture-bungee") {
            @Override public void execute(CommandSender sender, String[] args) {
                if (sender != getProxy().getConsole()) return;
                if (Arrays.equals(args, new String[]{"status"})) System.out.println("BUNGEE_STATUS api=" + CGLuckPermsHelper.isAvailable() + " native=" + FloodgateIdentity.isAvailable());
                else if (Arrays.equals(args, new String[]{"missing-id"})) CGLuckPermsHelper.hasPermission(null, "connectionguard.exemption.vpn").thenAccept(value -> System.out.println("BUNGEE_MISSING_ID grant=" + value));
            }
        });
        System.out.println("BUNGEE_FIXTURE_READY");
    }
    @EventHandler(priority = EventPriority.LOWEST) public void probe(LoginEvent event) {
        System.out.println("BUNGEE_CONNECTION proxyOnline=" + getProxy().getConfig().isOnlineMode() + " connectionOnline=" + event.getConnection().isOnlineMode()
                + " connected=" + event.getConnection().isConnected());
    }
    @Override public void onDisable() { if (provider != null) provider.close(); if (observer != null) observer.close(); }
}
