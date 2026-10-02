package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.google.gson.Gson;
import java.util.Optional;

/** Plain data only: no reflective access to java.util.Optional or platform internals. */
public final class CacheCodec {
    private static final Gson GSON = new Gson();
    private CacheCodec() { }
    public static String encode(Object result) { return GSON.toJson(result); }
    public static Optional<VpnResult> vpn(String json, String ip, long ttlMillis) {
        try {
            if (json == null || json.length() > 262144) return Optional.empty();
            VpnResult result = GSON.fromJson(json, VpnResult.class);
            if (result == null || !ip.equals(result.getIpAddress()) || result.getStatus() == null
                    || result.getStatus() == ProviderVote.Status.UNKNOWN
                    || result.isVpn() != (result.getStatus() == ProviderVote.Status.POSITIVE)
                    || !fresh(result.getCachedOn(), ttlMillis)) return Optional.empty();
            return Optional.of(result);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    public static Optional<GeoResult> geo(String json, String ip, long ttlMillis) {
        try {
            if (json == null || json.length() > 262144) return Optional.empty();
            GeoResult result = GSON.fromJson(json, GeoResult.class);
            if (result == null || !ip.equals(result.getIpAddress()) || result.getCountryName() == null
                    || !fresh(result.getCachedOn(), ttlMillis)) return Optional.empty();
            return Optional.of(result);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    private static boolean fresh(long cached, long ttl) {
        long now = System.currentTimeMillis();
        return cached > 0 && cached <= now && now - cached < ttl;
    }
}
