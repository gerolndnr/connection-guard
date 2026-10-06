package com.github.gerolndnr.connectionguard.core.geo;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.github.gerolndnr.connectionguard.core.http.ProviderAddresses;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckGeoProvider implements GeoProvider {
    private final String apiKey;
    private final boolean v3;
    private final transient com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient shared;

    public ProxyCheckGeoProvider(String apiKey) { this(apiKey, false); }
    public ProxyCheckGeoProvider(String apiKey, boolean v3) { this(apiKey, v3, null); }
    public ProxyCheckGeoProvider(String apiKey, boolean v3, com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient shared) {
        this.apiKey = apiKey; this.v3 = v3; this.shared = shared;
    }
    public boolean sharesVpnQuery() { return shared != null; }
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip, long remaining) {
        if (shared == null) return getGeoResult(ip);
        return com.github.gerolndnr.connectionguard.core.ConnectionGuard.proxyCheckResponse(shared, ip, true, remaining, () -> {})
                .thenApply(answer -> {
                    try { return answer.flatMap(json -> parse(ip, json)); }
                    catch (RuntimeException error) { throw ProviderHttp.failure(error); }
                });
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        if (shared != null) return getGeoResult(ipAddress, com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().getSettings().deadlineMillis);
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://proxycheck.io/" + (v3 ? "v3/" : "v2/") + ProviderAddresses.compressed(ipAddress) + "?key=" + apiKey
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
        JsonObject address = ProviderAddresses.response(json, ipAddress);
        GeoResult result = new GeoResult(ipAddress, ProviderHttp.string(address, "isocode"), "Unknown",
                ProviderHttp.string(address, "provider"));
        result.setAsn(DetectionFields.asn(address, "asn", false)); return Optional.of(result);
    }
    static Optional<GeoResult> parseV3(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        JsonObject address = ProviderAddresses.response(json, ipAddress);
        JsonObject location = DetectionFields.object(address, "location"), network = DetectionFields.object(address, "network");
        String country = DetectionFields.text(location, "isocode");
        if (country == null) return Optional.empty();
        String city = DetectionFields.text(location, "city"), isp = DetectionFields.text(network, "provider");
        GeoResult result = new GeoResult(ipAddress, country, city == null ? "Unknown" : city, isp == null ? "Unknown" : isp);
        result.setAsn(DetectionFields.asn(network, "asn", false)); return Optional.of(result);
    }
}
