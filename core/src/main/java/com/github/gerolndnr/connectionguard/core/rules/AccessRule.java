package com.github.gerolndnr.connectionguard.core.rules;

import java.util.UUID;

/** Immutable persisted admin rule; UUID targets require authenticated/trusted identity at evaluation. */
public final class AccessRule {
    public enum Effect { ALLOW, DENY, EXEMPT }
    public enum Scope { VPN, GEO, ALL }
    public enum Target { NETWORK, UUID }
    private final String id;
    private final Effect effect;
    private final Scope scope;
    private final Target type;
    private final String target;
    private final long expiresAt;
    private final String reason;
    private transient volatile IpNetwork network;
    public AccessRule(String id, Effect effect, Scope scope, String target, long expiresAt, String reason) {
        this.id = id; this.effect = effect; this.scope = scope; this.expiresAt = expiresAt; this.reason = reason;
        UUID uuid = null;
        try { uuid = UUID.fromString(target); if (!uuid.toString().equalsIgnoreCase(target)) uuid = null; }
        catch (IllegalArgumentException invalid) { }
        this.type = uuid == null ? Target.NETWORK : Target.UUID;
        this.target = uuid == null ? IpNetwork.parse(target).toString() : uuid.toString();
        validate();
    }
    public void validate() {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}") || effect == null || scope == null || type == null
                || target == null || expiresAt < 0 || reason == null || reason.length() < 1 || reason.length() > 200
                || reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid access rule (values redacted).");
        if (type == Target.NETWORK) network = IpNetwork.parse(target);
        else if (!UUID.fromString(target).toString().equalsIgnoreCase(target)) throw new IllegalArgumentException("UUID must use canonical form.");
    }
    public boolean matches(String ip, UUID uuid, boolean trusted, Scope requested, long now) {
        if ((expiresAt != 0 && now >= expiresAt) || (scope != Scope.ALL && scope != requested)) return false;
        return type == Target.NETWORK ? network.contains(ip) : trusted && uuid != null && target.equalsIgnoreCase(uuid.toString());
    }
    public String getId() { return id; }
    public Effect getEffect() { return effect; }
    public Scope getScope() { return scope; }
    public Target getType() { return type; }
    public String getTarget() { return target; }
    public long getExpiresAt() { return expiresAt; }
    public String getReason() { return reason; }
}
