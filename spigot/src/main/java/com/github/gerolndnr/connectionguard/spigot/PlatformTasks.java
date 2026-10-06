package com.github.gerolndnr.connectionguard.spigot;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Java-8 bridge to Paper/Folia schedulers, with the legacy Bukkit main-thread path. */
public final class PlatformTasks {
    private final Plugin plugin;
    private final Method globalGetter, globalExecute, entityGetter, entityExecute;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong nextWarning = new java.util.concurrent.atomic.AtomicLong();
    public PlatformTasks(Plugin plugin) {
        this.plugin = plugin;
        Method global = null, executeGlobal = null, entity = null, executeEntity = null;
        try {
            global = Bukkit.class.getMethod("getGlobalRegionScheduler");
            executeGlobal = global.getReturnType().getMethod("execute", Plugin.class, Runnable.class);
            entity = Entity.class.getMethod("getScheduler");
            executeEntity = entity.getReturnType().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        } catch (NoSuchMethodException absent) {
            // A partially present modern API must not fall back to an unsafe legacy scheduler.
            if (global != null || isFolia()) throw new IllegalStateException("Required region scheduler API is unavailable.");
        }
        globalGetter = global; globalExecute = executeGlobal; entityGetter = entity; entityExecute = executeEntity;
    }
    private static boolean isFolia() {
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }
    public String mode() { return globalGetter == null ? "legacy-main-thread" : "global-and-entity"; }
    public void close() { closed = true; }
    private Runnable active(Runnable work) { return () -> { if (!closed && plugin.isEnabled()) work.run(); }; }
    public void global(Runnable work) {
        if (closed || !plugin.isEnabled()) return;
        try {
            if (globalGetter == null) Bukkit.getScheduler().runTask(plugin, active(work));
            else globalExecute.invoke(globalGetter.invoke(null), plugin, active(work));
        } catch (ReflectiveOperationException | RuntimeException unavailable) { warn(); }
    }
    public void entity(Player player, Runnable work) {
        entity(player, work, () -> { });
    }
    /** Delay on the player's owning region; never use a legacy scheduler on Folia. */
    public void entityLater(Player player, long ticks, Runnable work) {
        if (ticks < 1) throw new IllegalArgumentException("Player task delay must be positive.");
        entity(player, work, () -> { }, ticks);
    }
    private void entity(Player player, Runnable work, Runnable retired) {
        entity(player, work, retired, 1L);
    }
    private void entity(Player player, Runnable work, Runnable retired, long ticks) {
        if (closed || !plugin.isEnabled()) return;
        try {
            Runnable ifRetired = active(retired);
            Runnable ifConnected = active(() -> { if (player.isOnline()) work.run(); else retired.run(); });
            if (entityGetter == null) {
                if (ticks == 1L) Bukkit.getScheduler().runTask(plugin, ifConnected);
                else Bukkit.getScheduler().runTaskLater(plugin, ifConnected, ticks);
            }
            else if (Boolean.FALSE.equals(entityExecute.invoke(entityGetter.invoke(player), plugin, ifConnected, ifRetired, ticks))) ifRetired.run();
            // Retirement never invokes player work or falls back to the legacy scheduler.
        } catch (ReflectiveOperationException | RuntimeException unavailable) { warn(); }
    }
    private void warn() {
        long now = System.currentTimeMillis(), next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + 30000))
            plugin.getLogger().warning("Platform task unavailable; notification/reply/command skipped (details redacted).");
    }
    public void reply(CommandSender sender, String text) {
        if (sender instanceof Player) entity((Player) sender, () -> sender.sendMessage(text));
        else global(() -> sender.sendMessage(text));
    }
    public void broadcast(String text, String permission) {
        global(() -> {
            if (Bukkit.getConsoleSender().hasPermission(permission)) Bukkit.getConsoleSender().sendMessage(text);
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) entity(player, () -> {
                if (player.hasPermission(permission)) player.sendMessage(text);
            });
        });
    }
    public void consoleCommand(String command) { global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)); }
    /** Resolve directory identity globally and access the selected player's address on its entity context. */
    public void target(String entry, Consumer<Target> found, Runnable invalid) {
        String literal = null;
        try {
            literal = com.github.gerolndnr.connectionguard.core.identity.Exemptions.normalize(entry);
        } catch (IllegalArgumentException notLiteral) { /* Could be an online player name/UUID. Never DNS. */ }
        // A consumer failure is not a parsing failure and must not trigger a second target lookup.
        if (literal != null) { found.accept(new Target(literal, literal)); return; }
        global(() -> {
            Player selected = Bukkit.getPlayerExact(entry);
            if (selected == null) {
                try { selected = Bukkit.getPlayer(UUID.fromString(entry)); }
                catch (IllegalArgumentException notUuid) { /* name not online */ }
            }
            if (selected == null) { invalid.run(); return; }
            final Player player = selected;
            entity(player, () -> {
                if (player.getAddress() == null || player.getAddress().getAddress() == null) { invalid.run(); return; }
                found.accept(new Target(player.getAddress().getAddress().getHostAddress(), player.getName()));
            }, invalid);
        });
    }
    public static final class Target {
        public final String ip, display;
        private Target(String ip, String display) { this.ip = ip; this.display = display; }
    }
}
