package com.github.gerolndnr.connectionguard.core.identity;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

/** A canonical login-phase platform connection, re-read rather than a captured mode flag. */
public final class AuthenticatedIdentity {
    private AuthenticatedIdentity() { }
    public static final class State {
        public final UUID uuid;
        public final String name;
        public final InetSocketAddress address;
        public final boolean authenticated, connected;
        public State(UUID uuid, String name, InetSocketAddress address, boolean authenticated, boolean connected) {
            this.uuid = uuid; this.name = name; this.address = address;
            this.authenticated = authenticated; this.connected = connected;
        }
    }
    public static final class Probe {
        private final UUID uuid;
        private final String name;
        private final InetSocketAddress address;
        private final Supplier<State> connection;
        private Probe(UUID uuid, String name, InetSocketAddress address, Supplier<State> connection) {
            this.uuid = uuid; this.name = name; this.address = address; this.connection = connection;
        }
        public boolean isBound() { return connection != null; }
        public boolean isBoundTo(UUID expectedUuid, String expectedName, InetSocketAddress expectedAddress) {
            return isBound() && uuid.equals(expectedUuid) && name.equals(expectedName) && address.equals(expectedAddress);
        }
        public boolean isCurrent() {
            if (!isBound()) return false;
            try { return matches(uuid, name, address, connection.get()); }
            catch (RuntimeException | LinkageError failure) { return false; }
        }
    }
    private static final Probe UNAVAILABLE = new Probe(null, null, null, null);
    public static Probe unavailable() { return UNAVAILABLE; }
    public static Probe capture(UUID uuid, String name, InetSocketAddress address, Supplier<State> connection) {
        if (connection == null) return UNAVAILABLE;
        try {
            if (!matches(uuid, name, address, connection.get())) return UNAVAILABLE;
            return new Probe(uuid, name, address, connection);
        } catch (RuntimeException | LinkageError failure) { return UNAVAILABLE; }
    }
    private static boolean matches(UUID uuid, String name, InetSocketAddress address, State state) {
        return uuid != null && name != null && !name.isEmpty() && name.length() <= 64
                && address != null && !address.isUnresolved() && address.getPort() > 0
                && state != null && state.authenticated && state.connected
                && uuid.equals(state.uuid) && name.equals(state.name) && address.equals(state.address);
    }
}
