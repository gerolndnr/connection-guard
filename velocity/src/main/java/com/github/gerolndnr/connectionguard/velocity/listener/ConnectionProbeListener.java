package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.probe.ConnectionProbes;
import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.lang.reflect.Field;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Experimental connection probe on Velocity (observe only). Reaches the player's Netty channel through Velocity internals. */
public final class ConnectionProbeListener {
    public static final int SAMPLES = 12, SAMPLE_SECONDS = 5;
    private final ConnectionGuardVelocityPlugin plugin;
    private final ConnectionProbes probes;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

    public ConnectionProbeListener(ConnectionGuardVelocityPlugin plugin, ConnectionProbes probes) { this.plugin = plugin; this.probes = probes; }

    @Subscribe(order = PostOrder.LAST)
    public void preLogin(PreLoginEvent event) {
        Object channel = channel(field(event.getConnection(), "delegate"), "getConnection");
        probes.preLogin(key(event.getConnection().getRemoteAddress()), channel);
    }

    @Subscribe
    public void joined(PostLoginEvent event) {
        Player player = event.getPlayer();
        Object channel = channel(player, "getConnection");
        InetSocketAddress address = player.getRemoteAddress();
        SocketAddress peer = remote(channel);
        boolean behindProxy = peer instanceof InetSocketAddress && !((InetSocketAddress) peer).getAddress().equals(address.getAddress());
        probes.joined(player.getUniqueId(), player.getUsername(), key(address), address.getAddress() instanceof Inet6Address, behindProxy, channel);
        AtomicInteger count = new AtomicInteger();
        tasks.put(player.getUniqueId(), plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
            int n = count.incrementAndGet();
            probes.sample(player.getUniqueId(), player.getPing(), channel, n >= SAMPLES);
            if (n >= SAMPLES) cancel(player.getUniqueId());
        }).delay(SAMPLE_SECONDS, TimeUnit.SECONDS).repeat(SAMPLE_SECONDS, TimeUnit.SECONDS).schedule());
    }

    @Subscribe
    public void left(DisconnectEvent event) { cancel(event.getPlayer().getUniqueId()); probes.left(event.getPlayer().getUniqueId()); }

    private void cancel(UUID uuid) { ScheduledTask task = tasks.remove(uuid); if (task != null) task.cancel(); }

    private static String key(InetSocketAddress address) { return address.getAddress().getHostAddress() + "|" + address.getPort(); }

    /** connection.getConnection() is Velocity's MinecraftConnection; its private "channel" field is the Netty channel. */
    private static Object channel(Object owner, String getter) {
        try {
            if (owner == null) return null;
            Object connection = owner.getClass().getMethod(getter).invoke(owner);
            return field(connection, "channel");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }
    private static Object field(Object owner, String name) {
        for (Class<?> type = owner == null ? null : owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException next) { /* look in the superclass */ }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }
        return null;
    }
    private static SocketAddress remote(Object channel) {
        try { return channel == null ? null : (SocketAddress) channel.getClass().getMethod("remoteAddress").invoke(channel); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }
}
