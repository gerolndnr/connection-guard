package com.github.gerolndnr.connectionguard.api.v1;
import java.util.*;
/** Immutable literal address and optional current verified identity; no platform/player object. */
public final class AdmissionRequest {
    private final String ip; private final UUID uuid; private final DecisionObservation.IdentityTrust trust;
    private final DecisionObservation.Platform platform; private final long due;
    public AdmissionRequest(String address, UUID verifiedUuid, DecisionObservation.IdentityTrust trust,
                            DecisionObservation.Platform platform, long deadlineNanos) {
        this.ip=com.github.gerolndnr.connectionguard.core.identity.Exemptions.normalize(address);
        this.trust=Objects.requireNonNull(trust);this.platform=Objects.requireNonNull(platform);this.due=deadlineNanos;
        if(verifiedUuid!=null && trust!=DecisionObservation.IdentityTrust.AUTHENTICATED
                && trust!=DecisionObservation.IdentityTrust.FLOODGATE && trust!=DecisionObservation.IdentityTrust.VERIFIED_FORWARDING)
            throw new IllegalArgumentException("An admission UUID requires current verified provenance.");
        this.uuid=verifiedUuid;
    }
    public String getIp(){return ip;} public Optional<UUID> getVerifiedUuid(){return Optional.ofNullable(uuid);}
    public DecisionObservation.IdentityTrust getIdentityTrust(){return trust;}
    public DecisionObservation.Platform getPlatform(){return platform;}
    public long getRemainingMillis(){return Math.max(0,(due-System.nanoTime()+999999)/1000000);}
}
