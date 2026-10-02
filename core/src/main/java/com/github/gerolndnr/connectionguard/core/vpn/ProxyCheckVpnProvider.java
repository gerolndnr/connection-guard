package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import java.util.Map;
import java.util.EnumMap;
import java.util.Locale;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckVpnProvider implements VpnProvider {
    private final String apiKey;
    private final boolean v3;

    public ProxyCheckVpnProvider(String apiKey) { this(apiKey, false); }
    public ProxyCheckVpnProvider(String apiKey, boolean v3) { this.apiKey = apiKey; this.v3 = v3; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://proxycheck.io/" + (v3 ? "v3/" : "v2/") + ipAddress + "?key=" + apiKey
                        + (v3 ? "&ver=24-June-2026&tag=0" : "&vpn=1&asn=1&risk=1&tag=0")).build(), "ProxyCheckVpnProvider");
                return json.isPresent() ? (v3 ? parseV3(ipAddress, json.get()) : parse(ipAddress, json.get())) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        JsonObject address = json.getAsJsonObject(ipAddress);
        String proxy = ProviderHttp.string(address, "proxy");
        if (!proxy.equalsIgnoreCase("yes") && !proxy.equalsIgnoreCase("no")) return Optional.empty();
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        String type = DetectionFields.text(address, "type");
        if (type != null) {
            // v2 reports one type. Never infer that all other types are false.
            String label = type.toUpperCase(Locale.ROOT);
            if (label.equals("VPN") || label.equals("TOR") || label.equals("HOSTING")) types.put(DetectionDetails.Type.valueOf(label), true);
            else if (label.startsWith("SOCKS") || label.equals("HTTP") || label.equals("HTTPS") || label.equals("SHADOWSOCKS") || label.equals("OPENVPN")) types.put(DetectionDetails.Type.PROXY, true);
        }
        VpnResult result = new VpnResult(ipAddress, proxy.equalsIgnoreCase("yes"));
        result.setDetails(new DetectionDetails(types, DetectionFields.asn(address, "asn", false), DetectionFields.text(address, "provider"),
                null, DetectionFields.text(address, "isocode"), DetectionFields.score(address, "risk"), null));
        return Optional.of(result);
    }
    static Optional<VpnResult> parseV3(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        JsonObject address = json.getAsJsonObject(ipAddress);
        JsonObject detections = DetectionFields.object(address, "detections");
        Boolean anonymous = DetectionFields.bool(detections, "anonymous");
        if (anonymous == null) return Optional.empty();
        JsonObject network = DetectionFields.object(address, "network");
        JsonObject operator = DetectionFields.object(address, "operator");
        String operatorName = DetectionFields.text(operator, "name");
        VpnResult result = new VpnResult(ipAddress, anonymous, Optional.ofNullable(operatorName));
        result.setDetails(new DetectionDetails(DetectionFields.types(detections, DetectionDetails.Type.VPN, DetectionDetails.Type.PROXY,
                DetectionDetails.Type.TOR, DetectionDetails.Type.HOSTING), DetectionFields.asn(network, "asn", false),
                DetectionFields.text(network, "provider"), operatorName,
                DetectionFields.text(DetectionFields.object(address, "location"), "isocode"),
                DetectionFields.score(address, "risk"), DetectionFields.score(detections, "confidence")));
        return Optional.of(result);
    }
}
