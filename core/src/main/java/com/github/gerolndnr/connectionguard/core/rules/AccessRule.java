package com.github.gerolndnr.connectionguard.core.rules;

import java.util.UUID;

/** Immutable persisted admin rule; UUID targets require authenticated/trusted identity at evaluation. */
public final class AccessRule {
    public enum Effect { ALLOW, DENY, EXEMPT }
    public enum Scope { VPN, GEO, ALL }
    public enum Target { NETWORK, UUID, ASN, ISP, OPERATOR, COUNTRY, TYPE, RISK, CONFIDENCE }
    private final String id;
    private final Effect effect;
    private final Scope scope;
    private final Target type;
    private final String target;
    private final long expiresAt;
    private final String reason;
    private transient volatile IpNetwork network;
    public AccessRule(String id, Effect effect, Scope scope, String target, long expiresAt, String reason) {
        if (target == null) throw new IllegalArgumentException("Target required.");
        this.id = id; this.effect = effect; this.scope = scope; this.expiresAt = expiresAt; this.reason = reason;
        String selector = target == null ? "" : target;
        Target rich = null;
        int colon = selector.indexOf(':');
        if (colon > 0) {
            try { Target candidate = Target.valueOf(selector.substring(0, colon).toUpperCase(java.util.Locale.ROOT));
                if (candidate != Target.NETWORK && candidate != Target.UUID) rich = candidate;
            } catch (IllegalArgumentException ignored) { }
        }
        if (rich != null) {
            this.type = rich;
            this.target = selector;
            validate(); return;
        }
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
        else if (type == Target.UUID) {
            if (!UUID.fromString(target).toString().equalsIgnoreCase(target)) throw new IllegalArgumentException("UUID must use canonical form.");
        } else {
            if (!target.regionMatches(true, 0, type.name() + ":", 0, type.name().length() + 1)) throw new IllegalArgumentException("Invalid selector.");
            String value = value();
            if (value.length() < 1 || value.length() > 200 || value.chars().anyMatch(Character::isISOControl) || value.indexOf('\u00a7') >= 0) throw new IllegalArgumentException("Invalid selector.");
            if (type == Target.ASN && !value.matches("(?i)(AS)?[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid ASN selector.");
            if (type == Target.ASN && Long.parseLong(value.toUpperCase(java.util.Locale.ROOT).replace("AS", "")) > 4294967295L) throw new IllegalArgumentException("Invalid ASN selector.");
            if (type == Target.COUNTRY && !value.matches("[A-Z]{2}")) throw new IllegalArgumentException("Use uppercase country ISO code.");
            if (type == Target.TYPE) com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.valueOf(value.toUpperCase(java.util.Locale.ROOT));
            if (type == Target.RISK || type == Target.CONFIDENCE) {
                int split = value.lastIndexOf(':');
                if (split < 1 || !value.substring(0, split).matches("[a-zA-Z0-9_#.-]{1,100}") || !value.substring(split+1).matches("[0-9]{1,3}")) throw new IllegalArgumentException("Score rule requires exact source:threshold.");
                if (Integer.parseInt(value.substring(split+1)) > 100) throw new IllegalArgumentException("Score threshold exceeds 100.");
            }
        }
    }
    public boolean matches(String ip, UUID uuid, boolean trusted, Scope requested, long now) {
        if (!active(requested, now) || isMetadata()) return false;
        return type == Target.NETWORK ? network.contains(ip) : trusted && uuid != null && target.equalsIgnoreCase(uuid.toString());
    }
    public boolean active(Scope requested, long now) { return (expiresAt == 0 || now < expiresAt) && (scope == Scope.ALL || scope == requested); }
    public boolean isMetadata() { return type != Target.NETWORK && type != Target.UUID; }
    public String value() { return target.substring(target.indexOf(':') + 1); }
    public String getId() { return id; }
    public Effect getEffect() { return effect; }
    public Scope getScope() { return scope; }
    public Target getType() { return type; }
    public String getTarget() { return target; }
    public long getExpiresAt() { return expiresAt; }
    public String getReason() { return reason; }
}
