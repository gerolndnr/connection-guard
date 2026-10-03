package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Missing native SDK contract; synthetic loopback fixture only. */
public final class CGMissingIdentityBackendFixture extends JavaPlugin implements Listener {
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    @Override public void onEnable() {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-perm-fixture", "1", String.join("", Collections.nCopies(64, "d")), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            return java.util.concurrent.CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-perm-observer", observation -> getLogger().info(
            "NATIVE_RECORD outcome=" + observation.getOutcome() + " reason=" + observation.getReason()
            + " identity=" + observation.getIdentityTrust() + " vpn=" + observation.getVpnCheck()));
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        com.github.gerolndnr.connectionguard.spigot.PaperLoginConnection connection=com.github.gerolndnr.connectionguard.spigot.PaperLoginConnection.read(event);
        getLogger().info("NATIVE_AUTH serverOnline="+Bukkit.getOnlineMode()+" connectionAddress="+(connection.address!=null)
            +" connected="+connection.connected.getAsBoolean()+" nativeBound="+com.github.gerolndnr.connectionguard.core.identity.FloodgateIdentity.capture(event.getUniqueId(),event.getName(),connection.address,connection.connected).isBound());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        if (Arrays.equals(args, new String[]{"status"})) getLogger().info("NATIVE_STATUS api=" + CGLuckPermsHelper.isAvailable()+" nativeIdentity="+com.github.gerolndnr.connectionguard.core.identity.FloodgateIdentity.isAvailable());
        else if (Arrays.equals(args, new String[]{"missing-id"})) CGLuckPermsHelper.hasPermission(null, "connectionguard.exemption.vpn")
            .thenAccept(grant -> getLogger().info("NATIVE_MISSING_ID grant=" + grant));
        return true;
    }
    @Override public void onDisable() { if (provider != null) provider.close(); if (observer != null) observer.close(); }
}
