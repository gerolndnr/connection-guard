package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.*;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.google.gson.JsonObject;
import okhttp3.Request;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Keyless commercial-use endpoint. Absent security flags are UNKNOWN, never clean. */
public final class IpQueryVpnProvider implements VpnProvider {
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder()
                        .url("https://api.ipquery.io/" + ProviderAddresses.compressed(ip)).build(), "IPQuery");
                return json.isPresent() ? parse(ip, json.get()) : Optional.empty();
            } catch (RuntimeException invalid) { throw ProviderHttp.failure(invalid); }
        });
    }
    static Optional<VpnResult> parse(String ip, JsonObject json) {
        DetectionFields.address(json, "ip", ip);
        JsonObject risk = DetectionFields.object(json, "risk");
        Boolean vpn = DetectionFields.bool(risk, "is_vpn"), proxy = DetectionFields.bool(risk, "is_proxy"), tor = DetectionFields.bool(risk, "is_tor");
        if (vpn == null || proxy == null || tor == null) return Optional.empty();
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        types.put(DetectionDetails.Type.VPN, vpn); types.put(DetectionDetails.Type.PROXY, proxy); types.put(DetectionDetails.Type.TOR, tor);
        Boolean hosting = DetectionFields.bool(risk, "is_datacenter");
        if (hosting != null) types.put(DetectionDetails.Type.HOSTING, hosting);
        JsonObject isp = DetectionFields.object(json, "isp");
        VpnResult result = new VpnResult(ip, vpn || proxy || tor);
        result.setDetails(new DetectionDetails(types, DetectionFields.asn(isp, "asn", false), DetectionFields.text(isp, "isp"), null,
                DetectionFields.text(DetectionFields.object(json, "location"), "country_code"), DetectionFields.score(risk, "risk_score"), null));
        return Optional.of(result);
    }
}
