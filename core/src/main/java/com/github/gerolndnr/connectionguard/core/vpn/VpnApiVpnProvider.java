package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class VpnApiVpnProvider implements VpnProvider {
    private final String apiKey;

    public VpnApiVpnProvider(String apiKey) { this.apiKey = apiKey; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://vpnapi.io/api/" + ipAddress + "?key=" + apiKey).build(), "VpnApiVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        DetectionFields.address(json, "ip", ipAddress);
        JsonObject security = json.getAsJsonObject("security");
        boolean vpn = ProviderHttp.bool(security, "vpn");
        boolean proxy = ProviderHttp.bool(security, "proxy");
        boolean tor = ProviderHttp.bool(security, "tor");
        boolean relay = ProviderHttp.bool(security, "relay");
        JsonObject network = DetectionFields.object(json, "network");
        VpnResult result = new VpnResult(ipAddress, vpn || proxy || tor || relay);
        result.setDetails(new DetectionDetails(DetectionFields.types(security, DetectionDetails.Type.VPN, DetectionDetails.Type.PROXY,
                DetectionDetails.Type.TOR, DetectionDetails.Type.RELAY), DetectionFields.asn(network, "autonomous_system_number", false),
                DetectionFields.text(network, "autonomous_system_organization"), null,
                DetectionFields.text(DetectionFields.object(json, "location"), "country_code"), null, null));
        return Optional.of(result);
    }
}
