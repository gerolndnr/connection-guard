package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpHubVpnProvider implements VpnProvider {
    private final String apiKey;

    public IpHubVpnProvider(String apiKey) { this.apiKey = apiKey; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://v2.api.iphub.info/ip/" + ipAddress).header("X-Key", apiKey).header("Accept-Version", "2.2").build(), "IpHubVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        DetectionFields.address(json, "ip", ipAddress);
        if (json.get("block") == null || !json.get("block").isJsonPrimitive()
                || !json.get("block").getAsJsonPrimitive().isNumber()) return Optional.empty();
        double block = json.get("block").getAsDouble();
        if (block != 0 && block != 1 && block != 2) return Optional.empty();
        VpnResult result = new VpnResult(ipAddress, block == 1);
        result.setDetails(new DetectionDetails(DetectionFields.types(DetectionFields.object(json, "proxyType"),
                DetectionDetails.Type.PROXY, DetectionDetails.Type.TOR, DetectionDetails.Type.RELAY, DetectionDetails.Type.HOSTING),
                DetectionFields.asn(json, "asn", false), DetectionFields.text(json, "isp"), null,
                DetectionFields.text(json, "countryCode"), null, null));
        return Optional.of(result);
    }
}
