package com.github.gerolndnr.connectionguard.core.identity;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Optional canonical Floodgate API. No API classes bundled and no identity inferred from UUID shape/name. */
public final class FloodgateIdentity {
    private static final int MAX_NATIVE_RECORDS = 65536;
    private FloodgateIdentity() { }
    interface NativeApi {
        Object find(UUID uuid) throws ReflectiveOperationException;
        boolean isCurrentApi() throws ReflectiveOperationException;
        int count() throws ReflectiveOperationException;
        Collection<?> active() throws ReflectiveOperationException;
        UUID id(Object player) throws ReflectiveOperationException;
        String name(Object player) throws ReflectiveOperationException;
        InetSocketAddress address(Object player) throws ReflectiveOperationException;
    }
    public static boolean isAvailable() { return ReflectiveApi.load().isPresent(); }
    public static Probe capture(UUID uuid, String name, InetSocketAddress address, BooleanSupplier connected) {
        Optional<NativeApi> api = ReflectiveApi.load();
        return api.isPresent() ? capture(api.get(), uuid, name, address, connected) : new Probe(false, null);
    }
    static Probe capture(NativeApi api, UUID uuid, String name, InetSocketAddress address, BooleanSupplier connected) {
        if (uuid == null || name == null || address == null || address.isUnresolved() || address.getPort() <= 0)
            return new Probe(false, null);
        try {
            Object player = api.find(uuid);
            if (player == null) return new Probe(false, null);
            BooleanSupplier proof = () -> matches(api, player, uuid, name, address, connected);
            return new Probe(true, proof.getAsBoolean() ? proof : null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return new Probe(true, null); }
    }
    private static boolean matches(NativeApi api, Object player, UUID uuid, String name, InetSocketAddress address, BooleanSupplier connected) {
        try {
            if (connected == null || !connected.getAsBoolean() || !api.isCurrentApi() || api.find(uuid) != player
                    || !uuid.equals(api.id(player)) || !name.equals(api.name(player)) || !address.equals(api.address(player))) return false;
            int count = api.count();
            if (count < 0 || count > MAX_NATIVE_RECORDS) return false;
            Collection<?> active = api.active();
            if (active == null || active.size() > MAX_NATIVE_RECORDS) return false;
            int inspected = 0;
            for (Object member : active) {
                if (++inspected > MAX_NATIVE_RECORDS) return false;
                if (member == player) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return false; }
    }
    public static final class Probe {
        private final boolean claimed;
        private final BooleanSupplier proof;
        private Probe(boolean claimed, BooleanSupplier proof) { this.claimed = claimed; this.proof = proof; }
        public boolean isClaimed() { return claimed; }
        public boolean isBound() { return proof != null; }
        public boolean isCurrent() {
            try { return proof != null && proof.getAsBoolean(); }
            catch (RuntimeException | LinkageError failure) { return false; }
        }
    }
    private static final class ReflectiveApi implements NativeApi {
        private final Object api, socketKey;
        private final Method instance, find, active, count, id, name, property;
        private ReflectiveApi(Class<?> apiType, Class<?> playerType, Class<?> propertyType) throws ReflectiveOperationException {
            instance = apiType.getMethod("getInstance"); api = instance.invoke(null);
            if (api == null) throw new IllegalStateException("Native API unavailable.");
            find = apiType.getMethod("getPlayer", UUID.class); active = apiType.getMethod("getPlayers"); count = apiType.getMethod("getPlayerCount");
            id = playerType.getMethod("getCorrectUniqueId"); name = playerType.getMethod("getCorrectUsername");
            property = playerType.getMethod("getProperty", propertyType);
            socketKey = propertyType.getField("SOCKET_ADDRESS").get(null);
        }
        private static Optional<NativeApi> load() {
            try {
                ClassLoader loader = FloodgateIdentity.class.getClassLoader();
                return Optional.of(new ReflectiveApi(Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader),
                        Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer", true, loader),
                        Class.forName("org.geysermc.floodgate.api.player.PropertyKey", true, loader)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Optional.empty(); }
        }
        public Object find(UUID uuid) throws ReflectiveOperationException { return find.invoke(api, uuid); }
        public boolean isCurrentApi() throws ReflectiveOperationException { return instance.invoke(null) == api; }
        public int count() throws ReflectiveOperationException { return (Integer) count.invoke(api); }
        public Collection<?> active() throws ReflectiveOperationException { return (Collection<?>) active.invoke(api); }
        public UUID id(Object player) throws ReflectiveOperationException { return (UUID) id.invoke(player); }
        public String name(Object player) throws ReflectiveOperationException { return (String) name.invoke(player); }
        public InetSocketAddress address(Object player) throws ReflectiveOperationException { return (InetSocketAddress) property.invoke(player, socketKey); }
    }
}
