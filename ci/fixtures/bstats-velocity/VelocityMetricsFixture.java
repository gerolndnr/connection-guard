package fixture;

import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.*;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** Actual SDK collectors with telemetry disabled. Never submit data or install on a public proxy. */
public final class VelocityMetricsFixture {
    private final ProxyServer proxy;
    @Inject public VelocityMetricsFixture(ProxyServer proxy) { this.proxy = proxy; }
    private Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register("fixture-metrics", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            try {
                Object plugin = ConnectionGuardVelocityPlugin.getInstance();
                Object metrics = field(plugin, "metrics"), base = field(metrics, "metricsBase");
                if (!Boolean.FALSE.equals(field(base, "enabled")) || !Integer.valueOf(22913).equals(field(base, "serviceId")))
                    throw new IllegalStateException("Actual SDK config or service mismatch");
                Class<?> builderType = base.getClass().getClassLoader().loadClass(base.getClass().getPackage().getName() + ".json.JsonObjectBuilder");
                Object builder = builderType.getConstructor().newInstance();
                for (String consumer : new String[]{"appendPlatformDataConsumer", "appendServiceDataConsumer"})
                    ((Consumer<Object>) field(base, consumer)).accept(builder);
                String json = builderType.getMethod("build").invoke(builder).toString();
                if (!json.contains("\"playerAmount\"") || !json.contains("\"managedServers\"") || !json.contains("\"pluginVersion\"")
                        || json.contains("playerName") || json.contains("playerUuid") || json.contains("playerIp"))
                    throw new IllegalStateException("Unexpected actual metrics collector payload");
                invocation.source().sendMessage(Component.text("METRICS_VERIFIED service=22913 enabled=false initialized=true collectors=true"));
            } catch (ReflectiveOperationException | RuntimeException invalid) {
                invocation.source().sendMessage(Component.text("METRICS_FAILED " + invalid.getClass().getSimpleName()));
            }
        });
    }
}
