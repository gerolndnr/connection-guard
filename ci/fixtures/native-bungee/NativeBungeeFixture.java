package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.identity.*;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.event.*;
import net.luckperms.api.*;
import net.luckperms.api.node.Node;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned native gateway/permission boundaries; never a real account-authentication fixture. */
public final class NativeBungeeFixture extends Plugin implements Listener {
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean hold;
    private volatile CompletableFuture<DetectionObservation> pending;
    private volatile FloodgateIdentity.Probe last;
    private volatile FloodgatePlayer lastRecord;
    private volatile PendingConnection lastConnection;
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    private static final String PERMISSION = "connectionguard.exemption.vpn";
    @Override public void onEnable() {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-bungee-fixture", "1",
                String.join("", Collections.nCopies(64, "f")), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected owned address");
            calls.incrementAndGet();
            if (hold) { pending = new CompletableFuture<>(); System.out.println("BUNGEE_WAITING"); return pending; }
            return CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-bungee-observer", o ->
            System.out.println("BUNGEE_DECISION outcome=" + o.getOutcome() + " reason=" + o.getReason()
                    + " identity=" + o.getIdentityTrust() + " vpn=" + o.getVpnCheck()));
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("fixture-bungee") {
            @Override public void execute(CommandSender sender, String[] args) {
                if (sender != getProxy().getConsole()) return;
                if (Arrays.equals(args, new String[]{"status"})) {
                    System.out.println("BUNGEE_STATUS api=" + CGLuckPermsHelper.isAvailable() + " native=" + FloodgateIdentity.isAvailable()
                            + " enabled=" + com.github.gerolndnr.connectionguard.core.ConnectionGuard.getSettings().nativeFloodgateIdentity
                            + " unregistered=" + (FloodgateApi.getInstance().getPlayer(offline("CGUnknown")) != null) + " calls=" + calls.get());
                } else if (Arrays.equals(args, new String[]{"missing-id"})) {
                    CGLuckPermsHelper.hasPermission(null, PERMISSION).thenAccept(value -> System.out.println("BUNGEE_MISSING_ID grant=" + value));
                } else if (Arrays.equals(args, new String[]{"prepare"})) {
                    grant(offline("CGNativeAllowed"), "native-fixture")
                        .thenCompose(v -> grant(offline("CGNativeWrong"), "different-fixture"))
                        .thenCompose(v -> grant(UUID.fromString("00000000-0000-0000-0000-000000000123"), "native-fixture"))
                        .thenCompose(v -> grant(UUID.fromString("dddddddd-1111-4222-8333-444444444444"), "different-fixture"))
                        .thenCompose(v -> CGLuckPermsHelper.hasPermission(offline("CGNativeAllowed"), PERMISSION))
                        .thenCompose(allowed -> CGLuckPermsHelper.hasPermission(offline("CGNativeWrong"), PERMISSION).thenAccept(wrong ->
                            System.out.println("BUNGEE_PREPARED allowed=" + allowed + " wrong=" + wrong + " staticServer="
                                + LuckPermsProvider.get().getContextManager().getStaticQueryOptions().context().contains("server", "native-fixture"))))
                        .exceptionally(error -> { System.out.println("BUNGEE_PREPARE_FAILED"); return null; });
                } else if (Arrays.equals(args, new String[]{"hold"})) {
                    hold = true; pending = null; System.out.println("BUNGEE_HOLD");
                } else if (Arrays.equals(args, new String[]{"release"})) {
                    CompletableFuture<DetectionObservation> value = pending; hold = false;
                    if (value == null) throw new IllegalStateException("No owned current wait");
                    value.complete(DetectionObservation.negative(DetectionMetadata.empty())); System.out.println("BUNGEE_RELEASED");
                } else if (Arrays.equals(args, new String[]{"retire-native-record"})) {
                    if (lastRecord == null) throw new IllegalStateException("No owned gateway record");
                    try { boolean removed = Boolean.TRUE.equals(FloodgateApi.getInstance().getClass()
                            .getMethod("setPendingRemove", FloodgatePlayer.class).invoke(FloodgateApi.getInstance(), lastRecord));
                        System.out.println("BUNGEE_NATIVE_RETIRE removed=" + removed);
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native retirement failed", error); }
                } else if (Arrays.equals(args, new String[]{"retired"})) {
                    FloodgatePlayer record = lastRecord; PendingConnection connection = lastConnection;
                    boolean lookup = record != null && FloodgateApi.getInstance().getPlayer(record.getCorrectUniqueId()) == record;
                    boolean active = record != null && FloodgateApi.getInstance().getPlayers().stream().anyMatch(p -> p == record);
                    boolean recapture = record != null && FloodgateIdentity.capture(record.getCorrectUniqueId(), record.getCorrectUsername(),
                            connection.getAddress(), () -> true).isBound();
                    System.out.println("BUNGEE_RETIRED captured=" + (last != null && last.isBound()) + " current=" + (last != null && last.isCurrent())
                            + " lookup=" + lookup + " active=" + active + " recapture=" + recapture + " connected=" + (connection != null && connection.isConnected()));
                }
            }
        });
        System.out.println("BUNGEE_FIXTURE_READY");
    }
    private CompletableFuture<Void> grant(UUID uuid, String context) {
        LuckPerms api = LuckPermsProvider.get();
        return api.getUserManager().loadUser(uuid).thenCompose(user -> {
            user.data().add(Node.builder(PERMISSION).withContext("server", context).build());
            return api.getUserManager().saveUser(user);
        });
    }
    private static UUID offline(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    @EventHandler(priority = EventPriority.LOWEST) public void deliberatelyOffline(PreLoginEvent event) {
        String name = event.getConnection().getName();
        if (getProxy().getConfig().isOnlineMode() && (name.equals("CGNativeAllowed") || name.equals("CGNativeWrong")))
            event.getConnection().setOnlineMode(false);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void nativeProbe(LoginEvent event) {
        PendingConnection connection = event.getConnection();
        UUID uuid = connection.getUniqueId(); String name = connection.getName(); InetSocketAddress address = connection.getAddress();
        FloodgatePlayer record = FloodgateApi.getInstance().getPlayer(uuid);
        FloodgateIdentity.Probe proof = FloodgateIdentity.capture(uuid, name, address, connection::isConnected);
        boolean canonical = record != null && record.getCorrectUniqueId().equals(uuid) && record.getCorrectUsername().equals(name);
        boolean wrongSocket = FloodgateIdentity.capture(uuid, name, new InetSocketAddress(address.getAddress(), address.getPort() == 65535 ? 65534 : address.getPort() + 1), connection::isConnected).isBound();
        boolean alias = record != null && record.isLinked() && FloodgateIdentity.capture(record.getJavaUniqueId(), name, address, connection::isConnected).isBound();
        last = proof; lastRecord = record; lastConnection = connection;
        System.out.println("BUNGEE_CONNECTION native=" + (record != null) + " bound=" + proof.isBound() + " current=" + proof.isCurrent()
                + " canonical=" + canonical + " wrongSocket=" + wrongSocket + " alias=" + alias + " linked=" + (record != null && record.isLinked())
                + " proxyOnline=" + getProxy().getConfig().isOnlineMode() + " connectionOnline=" + connection.isOnlineMode() + " connected=" + connection.isConnected());
    }
    @Override public void onDisable() {
        if (pending != null) pending.cancel(false); if (provider != null) provider.close(); if (observer != null) observer.close();
    }
}
