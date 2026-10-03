package com.github.gerolndnr.connectionguard.spigot;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import com.github.gerolndnr.connectionguard.core.identity.ForwardedIdentity;

/** Optional modern Paper connection data; legacy API absence supplies no native session proof. */
public final class PaperLoginConnection {
    public final InetSocketAddress address;
    public final BooleanSupplier connected;
    private final Object event, connection;
    private PaperLoginConnection(InetSocketAddress address, BooleanSupplier connected, Object event, Object connection) {
        this.address = address; this.connected = connected; this.event = event; this.connection = connection;
    }
    public static PaperLoginConnection read(Object event) {
        try {
            Object connection = event.getClass().getMethod("getConnection").invoke(event);
            if (connection == null) return unavailable();
            Class<?> type = Class.forName("io.papermc.paper.connection.PlayerConnection", false, event.getClass().getClassLoader());
            Method address = type.getMethod("getClientAddress"), connected = type.getMethod("isConnected");
            Object remote = address.invoke(connection);
            if (!(remote instanceof InetSocketAddress)) return unavailable();
            return new PaperLoginConnection((InetSocketAddress) remote, () -> {
                try { return Boolean.TRUE.equals(connected.invoke(connection)); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return false; }
            }, event, connection);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return unavailable(); }
    }
    /** On the named implementations this profile exists at pre-login only after the modern MAC gate.
     * Ordinary offline/direct-auth paths assign it after this event. Public APIs only; no NMS/stack probes.
     */
    public ForwardedIdentity.Probe forwardedProof(UUID uuid, String name) {
        if (!com.github.gerolndnr.connectionguard.core.ConnectionGuard.getSettings().nativePaperForwardingIdentity || connection == null)
            return ForwardedIdentity.unavailable();
        return ForwardedIdentity.capture(uuid, name, address, () -> {
            try {
                if (!qualifiedBuild(event.getClass().getClassLoader())
                        || !event.getClass().getName().equals("org.bukkit.event.player.AsyncPlayerPreLoginEvent")
                        || !connection.getClass().getName().equals("io.papermc.paper.connection.PaperPlayerLoginConnection")) return null;
                ClassLoader loader = event.getClass().getClassLoader();
                Class<?> type = Class.forName("io.papermc.paper.connection.PlayerLoginConnection", false, loader);
                Object profile = type.getMethod("getAuthenticatedProfile").invoke(connection);
                if (profile == null) return null;
                Class<?> profileType = Class.forName("com.destroystokyo.paper.profile.PlayerProfile", false, loader);
                UUID currentId = (UUID) profileType.getMethod("getId").invoke(profile);
                String currentName = (String) profileType.getMethod("getName").invoke(profile);
                InetSocketAddress currentAddress = (InetSocketAddress) type.getMethod("getClientAddress").invoke(connection);
                boolean canonicalEvent = currentId != null && currentId.equals(event.getClass().getMethod("getUniqueId").invoke(event))
                        && currentName != null && currentName.equals(event.getClass().getMethod("getName").invoke(event))
                        && currentAddress != null && currentAddress.getAddress().equals(event.getClass().getMethod("getAddress").invoke(event));
                return new ForwardedIdentity.State(currentId, currentName, currentAddress, canonicalEvent,
                        Boolean.TRUE.equals(type.getMethod("isConnected").invoke(connection)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        });
    }
    private static boolean qualifiedBuild(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName("io.papermc.paper.ServerBuildInfo", false, loader);
        Object build = type.getMethod("buildInfo").invoke(null);
        if (!"1.21.11".equals(type.getMethod("minecraftVersionId").invoke(build))) return false;
        String brand = type.getMethod("brandId").invoke(build).toString();
        OptionalInt number = (OptionalInt) type.getMethod("buildNumber").invoke(build);
        Optional<?> commit = (Optional<?>) type.getMethod("gitCommit").invoke(build);
        if (!number.isPresent() || !commit.isPresent()) return false;
        return (brand.equals("papermc:paper") && number.getAsInt() == 132 && commit.get().equals("c5eb079"))
                || (brand.equals("papermc:folia") && number.getAsInt() == 14 && commit.get().equals("529aabc"));
    }
    private static PaperLoginConnection unavailable() { return new PaperLoginConnection(null, () -> true, null, null); }
}
