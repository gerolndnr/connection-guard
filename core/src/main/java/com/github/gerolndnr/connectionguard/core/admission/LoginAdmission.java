package com.github.gerolndnr.connectionguard.core.admission;

/** Captured once per real login; a refusal is UNKNOWN and never a VPN classification. */
public final class LoginAdmission {
    public enum Reason { NONE, GLOBAL_LIMIT, IP_LIMIT, SUBNET_LIMIT, TRACKING_CAPACITY }
    private final Reason reason;
    private final long retryMillis;
    private final boolean deny, alert;
    LoginAdmission(Reason reason, long retryMillis, boolean deny, boolean alert) {
        this.reason = reason; this.retryMillis = retryMillis; this.deny = deny; this.alert = alert;
    }
    public boolean isAllowed() { return reason == Reason.NONE; }
    public Reason getReason() { return reason; }
    public long getRetryMillis() { return retryMillis; }
    public boolean shouldDeny() { return deny; }
    public boolean shouldAlert() { return alert; }
}
