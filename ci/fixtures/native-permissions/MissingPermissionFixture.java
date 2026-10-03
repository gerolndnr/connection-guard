package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** No permission plugin/API in the runtime; references only guard and platform classes. */
public final class MissingPermissionFixture {
    private final ProxyServer proxy;
    private final AtomicInteger calls = new AtomicInteger();
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    @Inject public MissingPermissionFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-perm-fixture", "1", String.join("", Collections.nCopies(64, "d")), true), ip -> {
            calls.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-perm-observer", observation ->
            System.out.println("NATIVE_RECORD outcome=" + observation.getOutcome() + " reason=" + observation.getReason()
                + " identity=" + observation.getIdentityTrust() + " vpn=" + observation.getVpnCheck()));
        proxy.getCommandManager().register("fixture-native", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            if (Arrays.equals(invocation.arguments(), new String[]{"status"})) {
                invocation.source().sendMessage(Component.text("NATIVE_STATUS api=" + CGLuckPermsHelper.isAvailable() + " calls=" + calls.get()));
            } else if (Arrays.equals(invocation.arguments(), new String[]{"missing-id"})) {
                CGLuckPermsHelper.hasPermission(null, "connectionguard.exemption.vpn").thenAccept(grant ->
                    invocation.source().sendMessage(Component.text("NATIVE_MISSING_ID grant=" + grant)));
            }
        });
    }
    @Subscribe public void authenticationProbe(LoginEvent event) {
        System.out.println("NATIVE_AUTH proxyOnline=" + proxy.getConfiguration().isOnlineMode() + " playerOnline=" + event.getPlayer().isOnlineMode());
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { provider.close(); observer.close(); }
}
