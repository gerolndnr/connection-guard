package com.github.gerolndnr.connectionguard.core.admission;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.function.Function;

/** Optional login lookup limits, independent of provider classification/failure policies. */
public final class AdmissionSettings {
    public final boolean enabled, denyConnections;
    public final int windowMillis, cooldownMillis, alarmCooldownMillis, globalLimit, ipLimit, subnetLimit;
    public final int ipv4Prefix, ipv6Prefix, trackingCapacity;
    public AdmissionSettings(Function<String, Object> value) {
        enabled = GuardSettings.bool(value, "overload.enabled", false);
        denyConnections = GuardSettings.bool(value, "overload.deny-connections", false);
        windowMillis = range(value, "window-ms", 10000, 100, 600000);
        cooldownMillis = range(value, "cooldown-ms", 10000, 100, 600000);
        alarmCooldownMillis = range(value, "alarm-cooldown-ms", 30000, 1000, 3600000);
        globalLimit = range(value, "global-attempts", 0, 0, 1000000);
        ipLimit = range(value, "per-ip-attempts", 0, 0, 1000000);
        subnetLimit = range(value, "per-subnet-attempts", 0, 0, 1000000);
        ipv4Prefix = range(value, "ipv4-prefix", 24, 0, 32);
        ipv6Prefix = range(value, "ipv6-prefix", 64, 0, 128);
        trackingCapacity = range(value, "tracking-capacity", 4096, 16, 16384);
        if (enabled && globalLimit == 0 && ipLimit == 0 && subnetLimit == 0)
            throw new IllegalArgumentException("Enabled overload protection requires an explicit nonzero limit.");
    }
    private static int range(Function<String, Object> value, String key, int fallback, int min, int max) {
        int result = GuardSettings.integer(value, "overload." + key, fallback);
        if (result < min || result > max) throw new IllegalArgumentException("overload." + key + " is outside " + min + ".." + max + ".");
        return result;
    }
    public static AdmissionSettings defaults() { return new AdmissionSettings(key -> null); }
}
