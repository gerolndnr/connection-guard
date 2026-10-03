package com.github.gerolndnr.connectionguard.core.identity;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One login's identity provenance. A configured declaration is never a verified session. */
public final class ConnectionIdentity {
    public enum Source { UNTRUSTED, AUTHENTICATED_CONNECTION, LEGACY_SERVER_ONLINE, DECLARED_FORWARDING, FLOODGATE_CONNECTION }
    private final UUID uuid;
    private final Source source;
    private final BooleanSupplier current;
    private ConnectionIdentity(UUID uuid, Source source, BooleanSupplier current) {
        this.uuid = uuid; this.source = uuid == null ? Source.UNTRUSTED : source; this.current = current;
    }
    public static ConnectionIdentity resolve(UUID uuid, String name, InetSocketAddress address, BooleanSupplier connected,
            AuthenticatedIdentity.Probe authenticatedConnection, boolean legacyServerOnline, boolean declaredForwarding, boolean floodgateEnabled) {
        if (uuid == null) return new ConnectionIdentity(null, Source.UNTRUSTED, connected);
        if (authenticatedConnection != null && authenticatedConnection.isBoundTo(uuid, name, address))
            return new ConnectionIdentity(uuid, Source.AUTHENTICATED_CONNECTION, authenticatedConnection::isCurrent);
        if (floodgateEnabled) {
            FloodgateIdentity.Probe probe = FloodgateIdentity.capture(uuid, name, address, connected);
            if (probe.isBound()) return nativeConnection(uuid, () ->
                    com.github.gerolndnr.connectionguard.core.ConnectionGuard.getSettings().nativeFloodgateIdentity && probe.isCurrent());
            // A native record or a failed native probe cannot turn a global flag into Java authentication.
            if (probe.isClaimed()) legacyServerOnline = false;
        }
        return new ConnectionIdentity(uuid, legacyServerOnline ? Source.LEGACY_SERVER_ONLINE
                : declaredForwarding ? Source.DECLARED_FORWARDING : Source.UNTRUSTED, connected);
    }
    static ConnectionIdentity nativeConnection(UUID uuid, BooleanSupplier proof) {
        return new ConnectionIdentity(uuid, Source.FLOODGATE_CONNECTION, proof);
    }
    public UUID uuid() { return uuid; }
    public Source source() { return source; }
    public boolean isTrusted() { return uuid != null && source != Source.UNTRUSTED; }
    public boolean isVerified() {
        return uuid != null && (source == Source.AUTHENTICATED_CONNECTION || source == Source.FLOODGATE_CONNECTION) && isCurrent();
    }
    public boolean requiresCurrentProof() { return source == Source.AUTHENTICATED_CONNECTION || source == Source.FLOODGATE_CONNECTION; }
    public boolean isCurrent() {
        try { return current != null && current.getAsBoolean(); }
        catch (RuntimeException | LinkageError failure) { return false; }
    }
    public DecisionObservation.IdentityTrust observationTrust() {
        switch (source) {
            case AUTHENTICATED_CONNECTION: return DecisionObservation.IdentityTrust.AUTHENTICATED;
            case LEGACY_SERVER_ONLINE: return DecisionObservation.IdentityTrust.PLATFORM_ONLINE;
            case DECLARED_FORWARDING: return DecisionObservation.IdentityTrust.FORWARDED;
            case FLOODGATE_CONNECTION: return DecisionObservation.IdentityTrust.FLOODGATE;
            default: return DecisionObservation.IdentityTrust.UNTRUSTED;
        }
    }
}
