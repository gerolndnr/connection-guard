package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import net.kyori.adventure.text.Component;
import net.luckperms.api.*;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.geysermc.floodgate.api.FloodgateApi;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real native plugin API calls; offline fixtures do not establish authenticated identity. */
public final class NativePermissionFixture {
    private static final String PERMISSION = "connectionguard.exemption.vpn";
    private final ProxyServer proxy;
    private final AtomicInteger calls = new AtomicInteger();
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    @Inject public NativePermissionFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-perm-fixture", "1", String.join("", Collections.nCopies(64, "d")), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            calls.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-perm-observer", observation ->
            System.out.println("NATIVE_RECORD outcome=" + observation.getOutcome() + " reason=" + observation.getReason()
                + " identity=" + observation.getIdentityTrust() + " vpn=" + observation.getVpnCheck()));
        proxy.getCommandManager().register("fixture-native", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            String[] args = invocation.arguments();
            if (Arrays.equals(args, new String[]{"status"})) {
                UUID id = id("CGNativeUnknown");
                boolean floodgate = FloodgateApi.getInstance().isFloodgatePlayer(id);
                invocation.source().sendMessage(Component.text("NATIVE_STATUS api=" + CGLuckPermsHelper.isAvailable()
                    + " floodgate=" + floodgate + " floodgatePlayer=" + (FloodgateApi.getInstance().getPlayer(id) != null) + " calls=" + calls.get()));
            } else if (Arrays.equals(args, new String[]{"missing-id"})) {
                CGLuckPermsHelper.hasPermission(null, PERMISSION).thenAccept(grant ->
                    invocation.source().sendMessage(Component.text("NATIVE_MISSING_ID grant=" + grant)));
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
                        invocation.source().sendMessage(Component.text("NATIVE_PREPARED allowed=" + allowed + " wrong=" + wrong
                            + " staticServer=" + api.getContextManager().getStaticQueryOptions().context().contains("server", "native-fixture")));
                        return null;
                })).exceptionally(error -> { invocation.source().sendMessage(Component.text("NATIVE_PREPARED failed=true")); return null; });
            } else if (Arrays.equals(args, new String[]{"unloaded"})) {
                UUID id = id("CGNativeUnloaded");
                boolean before = LuckPermsProvider.get().getUserManager().getUser(id) != null;
                CGLuckPermsHelper.hasPermission(id, PERMISSION).thenAccept(grant ->
                    invocation.source().sendMessage(Component.text("NATIVE_UNLOADED before=" + before + " grant=" + grant)));
            }
        });
    }
    @Subscribe(order = PostOrder.FIRST) public void offlineProbe(PreLoginEvent event) {
        // This fixture deliberately requests offline auth; it never creates an authenticated session.
        if (proxy.getConfiguration().isOnlineMode() && event.getResult().isAllowed()
                && (event.getUsername().equals("CGNativeAllowed") || event.getUsername().equals("CGNativeWrong")))
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
    }
    @Subscribe(order = PostOrder.FIRST) public void authenticationProbe(LoginEvent event) {
        System.out.println("NATIVE_AUTH proxyOnline=" + proxy.getConfiguration().isOnlineMode()
                + " playerOnline=" + event.getPlayer().isOnlineMode());
    }
    private static UUID id(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { provider.close(); observer.close(); }
}
