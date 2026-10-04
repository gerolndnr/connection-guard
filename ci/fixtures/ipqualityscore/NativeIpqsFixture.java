package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.text.Component;

/** Owned synthetic runtime probe. Console-only loopback seam; never install in production. */
public final class NativeIpqsFixture {
    private final ProxyServer proxy;
    private final AtomicInteger events = new AtomicInteger();
    private final AtomicReference<DecisionObservation> latest = new AtomicReference<>();
    private ObserverRegistration observer;
    @Inject public NativeIpqsFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        observer = ConnectionGuardApi.registerDecisionObserver("ipqs-fixture", value -> { latest.set(value); events.incrementAndGet(); });
        proxy.getCommandManager().register("fixture-ipqs", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            try {
                String[] args = invocation.arguments();
                if (args.length == 2 && args[0].equals("bind")) {
                    int port = Integer.parseInt(args[1]);
                    if (port < 1 || port > 65535 || !ConnectionGuard.getLookupRuntime().isIdle()) throw new IllegalArgumentException();
                    if (ConnectionGuard.getVpnProviders().size() != 1 || !(ConnectionGuard.getVpnProviders().get(0) instanceof IpQualityScoreVpnProvider)) throw new IllegalArgumentException();
                    VpnProvider original = ConnectionGuard.getVpnProviders().get(0);
                    Object key = field(original, "apiKey");
                    if (!"synthetic-runtime-key".equals(key)) throw new IllegalArgumentException();
                    Constructor<?> selected = null;
                    for (Constructor<?> candidate : IpQualityScoreVpnProvider.class.getDeclaredConstructors())
                        if (candidate.getParameterCount() == 5) selected = candidate;
                    if (selected == null) throw new IllegalArgumentException();
                    selected.setAccessible(true);
                    Class<?> endpointType = selected.getParameterTypes()[4];
                    Object endpoint = endpointType.getMethod("get", String.class).invoke(null, "http://127.0.0.1:" + port + "/api/json/ip");
                    VpnProvider local = (VpnProvider) selected.newInstance(key, field(original, "strictness"), field(original, "allowPublicAccessPoints"), field(original, "fast"), endpoint);
                    // Same native class/id/options; only its package-private HTTP fixture endpoint differs.
                    // Preserve real configured health counters/circuits across binds and reloads.
                    ConnectionGuard.getVpnProviders().set(0, local);
                } else if (!(args.length == 1 && args[0].equals("status"))) throw new IllegalArgumentException();
                DecisionObservation value = latest.get();
                String risk = "UNKNOWN", status = "none", cached = "none";
                if (value != null && value.getSources().size() > 0) {
                    DecisionObservation.Source source = value.getSources().get(0);
                    java.math.BigDecimal score = source.getObservation().getMetadata().getExactRisk();
                    if (score != null) risk = score.toString();
                    status = source.getObservation().getStatus().name(); cached = Boolean.toString(source.isFromCache());
                }
                invocation.source().sendMessage(Component.text("IPQS_FIXTURE events=" + events.get() + " risk=" + risk + " status=" + status + " cached=" + cached + " idle=" + ConnectionGuard.getLookupRuntime().isIdle()));
            } catch (ReflectiveOperationException | RuntimeException invalid) {
                invocation.source().sendMessage(Component.text("IPQS_FIXTURE rejected=true"));
            }
        });
    }
    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { if (observer != null) observer.close(); }
}
