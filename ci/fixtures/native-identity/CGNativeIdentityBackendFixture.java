package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.github.gerolndnr.connectionguard.core.identity.FloodgateIdentity;
import com.github.gerolndnr.connectionguard.spigot.PaperLoginConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.luckperms.api.*;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

/** Real native APIs with synthetic offline players; never install on production. */
public final class CGNativeIdentityBackendFixture extends JavaPlugin implements Listener {
    private static final String PERMISSION = "connectionguard.exemption.vpn";
    private final AtomicInteger calls = new AtomicInteger();
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    @Override public void onEnable() {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-perm-fixture", "1", String.join("", Collections.nCopies(64, "d")), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            calls.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-perm-observer", observation ->
            getLogger().info("NATIVE_RECORD outcome=" + observation.getOutcome() + " reason=" + observation.getReason()
                + " identity=" + observation.getIdentityTrust() + " vpn=" + observation.getVpnCheck()));
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        PaperLoginConnection connection=PaperLoginConnection.read(event);
        FloodgateIdentity.Probe probe=FloodgateIdentity.capture(event.getUniqueId(),event.getName(),connection.address,connection.connected);
        boolean wrongSocket=false;
        if (connection.address!=null) {
            java.net.InetSocketAddress remote=connection.address;
            wrongSocket=FloodgateIdentity.capture(event.getUniqueId(),event.getName(),
                new java.net.InetSocketAddress(remote.getAddress(),remote.getPort()==65535?65534:remote.getPort()+1),connection.connected).isBound();
        }
        getLogger().info("NATIVE_CONNECTION serverOnline="+Bukkit.getOnlineMode()+" api="+FloodgateIdentity.isAvailable()
            +" address="+(connection.address!=null)+" connected="+connection.connected.getAsBoolean()+" bound="+probe.isBound()+" current="+probe.isCurrent()+" wrongSocket="+wrongSocket);
    }
    @EventHandler public void joined(PlayerJoinEvent event) { getLogger().info("NATIVE_JOIN " + event.getPlayer().getName()); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        if (Arrays.equals(args, new String[]{"status"})) {
            UUID unknown = id("CGNativeUnknown");
            getLogger().info("NATIVE_STATUS api=" + CGLuckPermsHelper.isAvailable()
                + " floodgate=" + FloodgateApi.getInstance().isFloodgatePlayer(unknown)
                + " floodgatePlayer=" + (FloodgateApi.getInstance().getPlayer(unknown) != null) + " calls=" + calls.get());
        } else if (Arrays.equals(args, new String[]{"missing-id"})) {
            CGLuckPermsHelper.hasPermission(null, PERMISSION).thenAccept(grant -> getLogger().info("NATIVE_MISSING_ID grant=" + grant));
        } else if (Arrays.equals(args, new String[]{"prepare"})) {
            LuckPerms api = LuckPermsProvider.get();
            api.getUserManager().loadUser(id("CGNativeAllowed")).thenCompose(user -> {
                user.data().add(Node.builder(PERMISSION).withContext("server", "native-fixture").build());
                return api.getUserManager().saveUser(user);
            }).thenCompose(ignored -> api.getUserManager().loadUser(id("CGNativeWrong"))).thenCompose(user -> {
                user.data().add(Node.builder(PERMISSION).withContext("server", "different-fixture").build());
                return api.getUserManager().saveUser(user);
            }).thenCompose(ignored -> CGLuckPermsHelper.hasPermission(id("CGNativeAllowed"), PERMISSION)).thenCompose(allowed ->
                CGLuckPermsHelper.hasPermission(id("CGNativeWrong"), PERMISSION).thenApply(wrong -> {
                    getLogger().info("NATIVE_PREPARED allowed=" + allowed + " wrong=" + wrong
                        + " staticServer=" + api.getContextManager().getStaticQueryOptions().context().contains("server", "native-fixture"));
                    return null;
            })).exceptionally(error -> { getLogger().warning("NATIVE_PREPARED failed=true"); return null; });
        } else if (Arrays.equals(args, new String[]{"unloaded"})) {
            UUID unloaded = id("CGNativeUnloaded");
            boolean before = LuckPermsProvider.get().getUserManager().getUser(unloaded) != null;
            CGLuckPermsHelper.hasPermission(unloaded, PERMISSION).thenAccept(grant -> getLogger().info("NATIVE_UNLOADED before=" + before + " grant=" + grant));
        }
        return true;
    }
    private static UUID id(String name) { return name.equals("CGNativeAllowed")?UUID.fromString("00000000-0000-0000-0000-000000000123"):name.equals("CGNativeWrong")?UUID.fromString("dddddddd-1111-4222-8333-444444444444"):UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    @Override public void onDisable() { if (provider != null) provider.close(); if (observer != null) observer.close(); }
}
