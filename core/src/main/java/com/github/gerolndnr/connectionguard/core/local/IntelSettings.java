package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.function.Function;

/** Missing settings stay opt-in on existing installations, except the blocking defaults for relay and hosting (0.6.2):
 *  ban evasion through Cloudflare WARP or a rented server must not get in. Endpoint and trust key are not configurable. */
public final class IntelSettings {
    public enum Relay { ALLOW, VPN }
    /** BLOCK: a hosting/data-centre hit counts as a VPN; REVIEW: hosting alone only enriches the verdict. */
    public enum Hosting { BLOCK, REVIEW }
    /** ALLOW: a gaming booster node (Intel's BOOSTER list) is a negative verdict; VPN: it counts as a VPN. */
    public enum Booster { ALLOW, VPN }
    public final boolean enabled;
    public final int updateHours, maxAgeHours;
    public final Relay relay;
    public final Hosting hosting;
    public final Booster booster;
    public IntelSettings(Function<String,Object> value) {
        String prefix="provider.local.connectionguard-intel.";
        enabled=GuardSettings.bool(value,prefix+"enabled",false);
        updateHours=GuardSettings.integer(value,prefix+"update-hours",24);
        maxAgeHours=GuardSettings.integer(value,prefix+"max-age-hours",72);
        if(updateHours<0||updateHours>168||maxAgeHours<1||maxAgeHours>87600)throw new IllegalArgumentException("Invalid Intel update/freshness settings.");
        try {relay=Relay.valueOf(GuardSettings.string(value,prefix+"relay","VPN"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Intel relay must be ALLOW or VPN.");}
        try {hosting=Hosting.valueOf(GuardSettings.string(value,prefix+"hosting","BLOCK"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Intel hosting must be BLOCK or REVIEW.");}
        try {booster=Booster.valueOf(GuardSettings.string(value,prefix+"booster","ALLOW"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Intel booster must be ALLOW or VPN.");}
    }
}
