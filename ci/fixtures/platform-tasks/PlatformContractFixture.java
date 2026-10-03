package fixture;

import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import com.github.gerolndnr.connectionguard.spigot.PlatformTasks;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Synthetic local scheduler/command probe. Never install on production. */
public final class PlatformContractFixture extends JavaPlugin implements Listener {
    private PlatformTasks tasks;
    private volatile Player subject;
    private final AtomicInteger entities = new AtomicInteger(), globals = new AtomicInteger(), actions = new AtomicInteger(), errors = new AtomicInteger();
    private final AtomicInteger metricProbes = new AtomicInteger();
    @Override public void onEnable() {
        tasks = ConnectionGuardSpigotPlugin.getInstance().tasks();
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    private void check(boolean entity, Player player) {
        try {
            boolean correct = entity
                ? (Boolean) Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class).invoke(null, player)
                : (Boolean) Bukkit.class.getMethod("isGlobalTickThread").invoke(null);
            if (!correct) throw new IllegalStateException("Wrong scheduler context");
        } catch (ReflectiveOperationException | RuntimeException invalid) {
            errors.incrementAndGet(); getLogger().warning("FIXTURE context failure");
        }
    }
    @EventHandler public void joined(PlayerJoinEvent event) {
        subject = event.getPlayer();
        for (String permission : new String[]{"connectionguard.command.doctor", "connectionguard.command.info", "connectionguard.command.clear", "connectionguard.notify.vpn"})
            subject.addAttachment(this, permission, true);
        getLogger().info("FIXTURE joined " + subject.getName());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) return true;
        if (args.length != 1) return true;
        if (args[0].equals("metrics")) {
            metricsProbe();
        } else if (args[0].equals("targets")) {
            AtomicInteger callbacks = new AtomicInteger();
            boolean propagated = false;
            try {
                tasks.target("192.0.2.99", target -> {
                    callbacks.incrementAndGet();
                    throw new IllegalArgumentException("Synthetic consumer failure");
                }, () -> errors.incrementAndGet());
            } catch (IllegalArgumentException expected) { propagated = true; }
            if (!propagated || callbacks.get() != 1) errors.incrementAndGet();
            tasks.target("2001:db8::1", target -> {
                if (!target.ip.contains(":")) errors.incrementAndGet();
            }, () -> errors.incrementAndGet());
            getLogger().info("FIXTURE targets errors=" + errors.get());
        } else if (args[0].equals("action")) {
            check(false, null); actions.incrementAndGet(); getLogger().info("FIXTURE action=" + actions.get());
        } else if (args[0].equals("probe") && subject != null) {
            Player player = subject;
            tasks.global(() -> { check(false, null); globals.incrementAndGet(); getLogger().info("FIXTURE global=" + globals.get()); });
            tasks.entity(player, () -> {
                check(true, player); entities.incrementAndGet();
                player.performCommand("cg doctor");
                player.performCommand("cg info " + player.getName());
                player.performCommand("cg clear " + player.getUniqueId());
                tasks.reply(player, "FIXTURE entity reply");
                getLogger().info("FIXTURE entity=" + entities.get());
            });
        } else if (args[0].equals("retired") && subject != null) {
            // After disconnect the guard must skip this callback, with no legacy fallback.
            tasks.entity(subject, () -> { errors.incrementAndGet(); getLogger().warning("FIXTURE retired callback ran"); });
        } else if (args[0].equals("status")) {
            getLogger().info("FIXTURE status entity=" + entities.get() + " global=" + globals.get() + " actions=" + actions.get() + " errors=" + errors.get());
        }
        return true;
    }
    @SuppressWarnings("unchecked") private void metricsProbe() {
        // Exercise the actual dispatcher/collectors passed to pinned MetricsBase.
        // Telemetry is disabled; never call submitData or send a bStats request.
        try {
            Object plugin = ConnectionGuardSpigotPlugin.getInstance();
            java.lang.reflect.Field field = plugin.getClass().getDeclaredField("metrics"); field.setAccessible(true);
            Object adapter = field.get(plugin);
            field = adapter.getClass().getDeclaredField("metricsBase"); field.setAccessible(true);
            Object base = field.get(adapter);
            java.lang.reflect.Field dispatcherField = base.getClass().getDeclaredField("submitTaskConsumer"); dispatcherField.setAccessible(true);
            java.util.function.Consumer<Runnable> dispatcher = (java.util.function.Consumer<Runnable>) dispatcherField.get(base);
            Runnable collect = () -> {
                check(false, null);
                try {
                    Class<?> builderType = base.getClass().getClassLoader().loadClass(base.getClass().getPackage().getName() + ".json.JsonObjectBuilder");
                    Object builder = builderType.getConstructor().newInstance();
                    for (String name : new String[]{"appendPlatformDataConsumer", "appendServiceDataConsumer"}) {
                        java.lang.reflect.Field appender = base.getClass().getDeclaredField(name); appender.setAccessible(true);
                        ((java.util.function.Consumer<Object>) appender.get(base)).accept(builder);
                    }
                    String json = builderType.getMethod("build").invoke(builder).toString();
                    if (!json.contains("\"playerAmount\"") || !json.contains("\"pluginVersion\"")) throw new IllegalStateException("Metrics fields absent");
                    getLogger().info("FIXTURE metrics=" + metricProbes.incrementAndGet());
                } catch (ReflectiveOperationException | RuntimeException invalid) {
                    errors.incrementAndGet(); getLogger().warning("FIXTURE metrics failure");
                }
            };
            Thread worker = new Thread(() -> dispatcher.accept(collect), "CGFixture-metrics");
            worker.setDaemon(true); worker.start();
        } catch (ReflectiveOperationException | RuntimeException invalid) {
            errors.incrementAndGet(); getLogger().warning("FIXTURE metrics failure");
        }
    }
}
