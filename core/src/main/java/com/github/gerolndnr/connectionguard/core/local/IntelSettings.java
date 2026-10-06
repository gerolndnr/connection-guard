package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.function.Function;

/** Missing settings stay opt-in on existing installations. Endpoint and trust key are not configurable. */
public final class IntelSettings {
    public enum Relay { ALLOW, VPN }
    public final boolean enabled;
    public final int updateHours, maxAgeHours;
    public final Relay relay;
    public IntelSettings(Function<String,Object> value) {
        String prefix="provider.local.connectionguard-intel.";
        enabled=GuardSettings.bool(value,prefix+"enabled",false);
        updateHours=GuardSettings.integer(value,prefix+"update-hours",24);
        maxAgeHours=GuardSettings.integer(value,prefix+"max-age-hours",72);
        if(updateHours<0||updateHours>168||maxAgeHours<1||maxAgeHours>87600)throw new IllegalArgumentException("Invalid Intel update/freshness settings.");
        try {relay=Relay.valueOf(GuardSettings.string(value,prefix+"relay","ALLOW"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Intel relay must be ALLOW or VPN.");}
    }
}
