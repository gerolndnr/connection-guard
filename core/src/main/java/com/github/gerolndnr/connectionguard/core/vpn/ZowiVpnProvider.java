package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.google.gson.*;
import okhttp3.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Explicit VPN/proxy/Tor flags vote; hosting alone is UNKNOWN review evidence. */
public final class ZowiVpnProvider implements VpnProvider {
    private static final HttpUrl ENDPOINT = HttpUrl.get("https://api.zowi.gay");
    private final transient HttpUrl fixtureEndpoint;
    public ZowiVpnProvider() { this(null); }
    ZowiVpnProvider(HttpUrl fixtureEndpoint) { this.fixtureEndpoint = KeylessEndpoint.fixture(fixtureEndpoint); }
    @Override public String sourceName() { return "zowi"; }
    @Override public boolean stableSourceId() { return true; }
    Request request(String ip) {
        return new Request.Builder().url((fixtureEndpoint == null ? ENDPOINT : fixtureEndpoint).newBuilder()
                .addPathSegment(ProviderAddresses.compressed(ip)).build()).build();
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return ProviderHttp.submit(() -> {
            try { return ProviderHttp.readJson(request(ip), sourceName()).flatMap(json -> parse(ip, json)); }
            catch (RuntimeException invalid) { throw ProviderHttp.failure(invalid); }
        });
    }
    static Optional<VpnResult> parse(String ip, JsonObject json) {
        DetectionFields.address(json, "ip", ip);
        JsonObject security = DetectionFields.object(json, "security");
        if (security == null) return Optional.empty();
        Boolean vpn = DetectionFields.bool(DetectionFields.object(security, "vpn"), "detected");
        Boolean proxy = proxy(security), tor = DetectionFields.bool(security, "tor"), hosting = DetectionFields.bool(security, "hosting");
        boolean positive = Boolean.TRUE.equals(vpn) || Boolean.TRUE.equals(proxy) || Boolean.TRUE.equals(tor);
        if (!positive && (vpn == null || proxy == null || tor == null) && !Boolean.TRUE.equals(hosting)) return Optional.empty();
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        if (vpn != null) types.put(DetectionDetails.Type.VPN, vpn);
        if (proxy != null) types.put(DetectionDetails.Type.PROXY, proxy);
        if (tor != null) types.put(DetectionDetails.Type.TOR, tor);
        if (hosting != null) types.put(DetectionDetails.Type.HOSTING, hosting);
        VpnResult result = new VpnResult(ip, positive);
        result.setDetails(new DetectionDetails(types, null, null, null, null, null, null));
        if (!positive && Boolean.TRUE.equals(hosting)) result.setUnknown(FailureReason.NO_EVIDENCE);
        return Optional.of(result);
    }
    private static Boolean proxy(JsonObject security) {
        JsonElement proxy = security.get("proxy");
        return proxy != null && proxy.isJsonObject() ? DetectionFields.bool(proxy.getAsJsonObject(), "detected") : DetectionFields.bool(security, "proxy");
    }
}
