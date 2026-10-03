package com.github.gerolndnr.connectionguard.spigot;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;

/** Optional modern Paper connection data; legacy API absence supplies no native session proof. */
public final class PaperLoginConnection {
    public final InetSocketAddress address;
    public final BooleanSupplier connected;
    private PaperLoginConnection(InetSocketAddress address, BooleanSupplier connected) { this.address = address; this.connected = connected; }
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
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return unavailable(); }
    }
    private static PaperLoginConnection unavailable() { return new PaperLoginConnection(null, () -> true); }
}
