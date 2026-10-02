package com.github.gerolndnr.connectionguard.core.geo;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckGeoProvider implements GeoProvider {
    private final String apiKey;
    private final boolean v3;

    public ProxyCheckGeoProvider(String apiKey) { this(apiKey, false); }
    public ProxyCheckGeoProvider(String apiKey, boolean v3) { this.apiKey = apiKey; this.v3 = v3; }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://proxycheck.io/" + (v3 ? "v3/" : "v2/") + ipAddress + "?key=" + apiKey
                        + (v3 ? "&ver=24-June-2026&tag=0" : "&asn=1&tag=0")).build(), "ProxyCheckGeoProvider");
                return json.isPresent() ? (v3 ? parseV3(ipAddress, json.get()) : parse(ipAddress, json.get())) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<GeoResult> parse(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        JsonObject address = json.getAsJsonObject(ipAddress);
        GeoResult result = new GeoResult(ipAddress, ProviderHttp.string(address, "isocode"), "Unknown",
                ProviderHttp.string(address, "provider"));
        result.setAsn(DetectionFields.asn(address, "asn", false)); return Optional.of(result);
    }
    static Optional<GeoResult> parseV3(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        JsonObject address = json.getAsJsonObject(ipAddress);
        JsonObject location = DetectionFields.object(address, "location"), network = DetectionFields.object(address, "network");
        String country = DetectionFields.text(location, "isocode");
        if (country == null) return Optional.empty();
        String city = DetectionFields.text(location, "city"), isp = DetectionFields.text(network, "provider");
        GeoResult result = new GeoResult(ipAddress, country, city == null ? "Unknown" : city, isp == null ? "Unknown" : isp);
        result.setAsn(DetectionFields.asn(network, "asn", false)); return Optional.of(result);
    }
}
