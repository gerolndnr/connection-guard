package com.github.gerolndnr.connectionguard.velocity.listener;

import com.github.gerolndnr.connectionguard.core.probe.ConnectionProbes;
import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.util.GameProfile;

import java.lang.reflect.Field;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private final ScheduledTask expiry;

    public ConnectionProbeListener(ConnectionGuardVelocityPlugin plugin, ConnectionProbes probes) {
        this.plugin = plugin; this.probes = probes;
        // Logins that never join are written once they are a minute old, even on a quiet server.
        expiry = plugin.getProxyServer().getScheduler().buildTask(plugin, probes::expire).repeat(30, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void handshake(ConnectionHandshakeEvent event) { if (probes.active()) probes.handshake(key(event.getConnection().getRemoteAddress())); }

    @Subscribe(order = PostOrder.LAST)
    public void preLogin(PreLoginEvent event) {
        if (!probes.active()) return;
        InboundConnection connection = event.getConnection();
        Object channel = channel(field(connection, "delegate"), "getConnection");
        InetSocketAddress address = connection.getRemoteAddress();
        probes.preLogin(key(address), address.getAddress(), behindProxy(channel, address) ? null : channel,
                connection.getProtocolVersion().getProtocol(), connection.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null));
    }

    @Subscribe
    public void joined(PostLoginEvent event) {
        if (!probes.active()) return;
        Player player = event.getPlayer();
        Object channel = channel(player, "getConnection");
        InetSocketAddress address = player.getRemoteAddress();
        probes.joined(player.getUniqueId(), player.getUsername(), key(address), address.getAddress(), address.getAddress() instanceof Inet6Address,
                behindProxy(channel, address), channel);
        AtomicInteger count = new AtomicInteger();
        tasks.put(player.getUniqueId(), plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
            int n = count.incrementAndGet();
            boolean last = n >= SAMPLES;
            // Brand and settings arrive after the join, so the client is read only with the final sample.
            probes.sample(player.getUniqueId(), player.getPing(), channel, last, last ? client(player) : null);
            if (last) cancel(player.getUniqueId());
        }).delay(SAMPLE_SECONDS, TimeUnit.SECONDS).repeat(SAMPLE_SECONDS, TimeUnit.SECONDS).schedule());
    }

    @Subscribe
    public void left(DisconnectEvent event) {
        Player player = event.getPlayer();
        cancel(player.getUniqueId());
        if (!probes.probing(player.getUniqueId())) return;
        probes.left(player.getUniqueId(), client(player));
    }

    public void close() { expiry.cancel(); tasks.keySet().forEach(this::cancel); }

    private void cancel(UUID uuid) { ScheduledTask task = tasks.remove(uuid); if (task != null) task.cancel(); }

    private static ConnectionProbes.ClientInfo client(Player player) {
        try {
            String locale = player.hasSentPlayerSettings() && player.getPlayerSettings().getLocale() != null
                    ? player.getPlayerSettings().getLocale().toString() : null;
            return new ConnectionProbes.ClientInfo(player.getClientBrand(), locale, bedrockOs(player.getUniqueId()), skin(player.getGameProfile()));
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    /** The skin's texture URL, decoded from the signed "textures" property; the probe keeps only a keyed hash of it. */
    private static String skin(GameProfile profile) {
        for (GameProfile.Property property : profile.getProperties()) {
            if (!"textures".equals(property.getName())) continue;
            try {
                JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                return textures == null || !textures.has("SKIN") ? null : textures.getAsJsonObject("SKIN").get("url").getAsString();
            } catch (RuntimeException invalid) { return null; }
        }
        return null;
    }

    /** Floodgate's device OS for Bedrock players (Android, iOS, Windows, consoles), read by reflection when Floodgate is installed. */
    private static String bedrockOs(UUID uuid) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object player = instance == null ? null : api.getMethod("getPlayer", UUID.class).invoke(instance, uuid);
            if (player == null) return null;
            Object os = player.getClass().getMethod("getDeviceOs").invoke(player);
            // The enum's name (GOOGLE, IOS, WIN32...), not its display text.
            return os == null ? null : os instanceof Enum ? ((Enum<?>) os).name() : os.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) { return null; }
    }

    private static String key(InetSocketAddress address) { return address.getAddress().getHostAddress() + "|" + address.getPort(); }

    /** The socket peer is not the player: HAProxy/TCPShield in front, or a backend behind a proxy. TCP fields would describe the proxy. */
    private static boolean behindProxy(Object channel, InetSocketAddress address) {
        SocketAddress peer = remote(channel);
        return peer instanceof InetSocketAddress && !((InetSocketAddress) peer).getAddress().equals(address.getAddress());
    }

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
