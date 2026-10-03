package com.github.gerolndnr.connectionguard.api.v1;
import java.util.Objects;
/** A selected external policy fact, never VPN evidence, punishment creation or a grant. */
public final class AdmissionResponse {
    public enum Status { CLEAR, DENY, UNKNOWN }
    public enum Reason { NONE, ACTIVE_BAN, UNAVAILABLE, TIMEOUT, OVERLOADED, INVALID_RESPONSE, STALE_DATA, CANCELLED }
    private final Status status;private final Reason reason;private final long validUntil;
    private AdmissionResponse(Status status,Reason reason,long validUntil){
        this.status=Objects.requireNonNull(status);this.reason=Objects.requireNonNull(reason);
        if(validUntil<0 || (status==Status.CLEAR && reason!=Reason.NONE) || (status==Status.DENY && reason!=Reason.ACTIVE_BAN)
                || (status==Status.UNKNOWN && (reason==Reason.NONE || reason==Reason.ACTIVE_BAN))) throw new IllegalArgumentException("Invalid admission response.");
        this.validUntil=validUntil;
    }
    public static AdmissionResponse clear(){return new AdmissionResponse(Status.CLEAR,Reason.NONE,0);}
    public static AdmissionResponse activeBan(long validUntil){return new AdmissionResponse(Status.DENY,Reason.ACTIVE_BAN,validUntil);}
    public static AdmissionResponse unknown(Reason reason){return new AdmissionResponse(Status.UNKNOWN,reason,0);}
    public Status getStatus(){return status;}public Reason getReason(){return reason;}public long getValidUntil(){return validUntil;}
    public AdmissionResponse fresh(long now){return validUntil>0 && validUntil<=now?unknown(Reason.STALE_DATA):this;}
}
