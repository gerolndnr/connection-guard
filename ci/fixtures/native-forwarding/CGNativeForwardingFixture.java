package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.identity.ForwardedIdentity;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.github.gerolndnr.connectionguard.spigot.PaperLoginConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.luckperms.api.*;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual native gateway assertions with synthetic clients. Never install on production. */
public final class CGNativeForwardingFixture extends JavaPlugin implements Listener {
    private static final String PERMISSION = "connectionguard.exemption.vpn";
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CompletableFuture<DetectionObservation> held;
    private volatile AsyncPlayerPreLoginEvent captured;
    private volatile PaperLoginConnection connection;
    private volatile ForwardedIdentity.Probe proof;
    @Override public void onEnable() {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-forwarding-fixture", "1",
                String.join("", Collections.nCopies(64, "e")), true), ip -> {
            if (!ip.equals("127.0.0.1") && !ip.equals("203.0.113.10")) throw new IllegalArgumentException("Unexpected synthetic address");
            calls.incrementAndGet();
            CompletableFuture<DetectionObservation> pending = held;
            getLogger().info("FORWARD_PROVIDER ip=" + ip + " held=" + (pending != null));
            return pending != null ? pending : CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-forwarding-observer", observation ->
            getLogger().info("FORWARD_RECORD outcome=" + observation.getOutcome() + " reason=" + observation.getReason()
                + " identity=" + observation.getIdentityTrust() + " vpn=" + observation.getVpnCheck()));
        Bukkit.getPluginManager().registerEvents(this, this);
        io.papermc.paper.ServerBuildInfo build = io.papermc.paper.ServerBuildInfo.buildInfo();
        getLogger().info("FORWARD_BUILD brand=" + build.brandId() + " number=" + build.buildNumber().orElse(-1) + " commit=" + build.gitCommit().orElse("absent"));
    }
    @EventHandler(priority = EventPriority.LOWEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        PaperLoginConnection nativeConnection = PaperLoginConnection.read(event);
        ForwardedIdentity.Probe nativeProof = nativeConnection.forwardedProof(event.getUniqueId(), event.getName());
        captured = event; connection = nativeConnection; proof = nativeProof;
        getLogger().info("FORWARD_NATIVE name=" + event.getName() + " uuid=" + event.getUniqueId()
            + " ip=" + event.getAddress().getHostAddress() + " raw=" + ((java.net.InetSocketAddress) event.getConnection().getAddress()).getAddress().getHostAddress()
            + " bound=" + nativeProof.isBound() + " current=" + nativeProof.isCurrent()
            + " wrongUuid=" + nativeConnection.forwardedProof(id("CGFwOther"), event.getName()).isBound()
            + " wrongName=" + nativeConnection.forwardedProof(event.getUniqueId(), "CGFwOther").isBound()
            + " profile=" + (event.getConnection().getAuthenticatedProfile() != null)
            + " connected=" + nativeConnection.connected.getAsBoolean());
    }
    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent event) {
        getLogger().info("FORWARD_JOIN name=" + event.getPlayer().getName() + " uuid=" + event.getPlayer().getUniqueId());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        if (Arrays.equals(args, new String[]{"prepare"})) {
            LuckPerms api = LuckPermsProvider.get();
            api.getUserManager().loadUser(id("CGFwAllowed")).thenCompose(user -> {
                user.data().add(Node.builder(PERMISSION).withContext("server", "forwarding-fixture").build());
                return api.getUserManager().saveUser(user);
            }).thenCompose(ignored -> api.getUserManager().loadUser(id("CGFwWrong"))).thenCompose(user -> {
                user.data().add(Node.builder(PERMISSION).withContext("server", "different-fixture").build());
                return api.getUserManager().saveUser(user);
            }).thenCompose(ignored -> CGLuckPermsHelper.hasPermission(id("CGFwAllowed"), PERMISSION)).thenCompose(allowed ->
                CGLuckPermsHelper.hasPermission(id("CGFwWrong"), PERMISSION).thenApply(wrong -> {
                    getLogger().info("FORWARD_PREPARED allowed=" + allowed + " wrong=" + wrong
                        + " staticServer=" + api.getContextManager().getStaticQueryOptions().context().contains("server", "forwarding-fixture"));
                    return null;
            })).exceptionally(error -> { getLogger().warning("FORWARD_PREPARED failed=true"); return null; });
        } else if (Arrays.equals(args, new String[]{"hold"})) {
            if (held != null) throw new IllegalStateException("Already holding a lookup");
            held = new CompletableFuture<>(); getLogger().info("FORWARD_HOLD ready=true");
        } else if (Arrays.equals(args, new String[]{"release"})) {
            CompletableFuture<DetectionObservation> pending = held; held = null;
            if (pending == null) throw new IllegalStateException("No held lookup");
            pending.complete(DetectionObservation.negative(DetectionMetadata.empty()));
            getLogger().info("FORWARD_RELEASE completed=true");
        } else if (Arrays.equals(args, new String[]{"mutate"})) {
            AsyncPlayerPreLoginEvent event = captured;
            if (held == null || event == null || proof == null || !proof.isCurrent()) throw new IllegalStateException("Expected live captured native proof");
            event.setPlayerProfile(Bukkit.createProfile(event.getUniqueId(), "CGFwChanged"));
            getLogger().info("FORWARD_MUTATION bound=" + proof.isBound() + " current=" + proof.isCurrent()
                + " connected=" + connection.connected.getAsBoolean());
        } else if (Arrays.equals(args, new String[]{"status"})) {
            getLogger().info("FORWARD_STATUS calls=" + calls.get() + " current=" + (proof != null && proof.isCurrent())
                + " connected=" + (connection != null && connection.connected.getAsBoolean()));
        }
        return true;
    }
    private static UUID id(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    @Override public void onDisable() { if (held != null) held.cancel(false); if (provider != null) provider.close(); if (observer != null) observer.close(); }
}
