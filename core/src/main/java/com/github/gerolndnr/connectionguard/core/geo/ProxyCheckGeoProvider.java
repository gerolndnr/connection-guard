package com.github.gerolndnr.connectionguard.core.geo;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckGeoProvider implements GeoProvider {
    private final String apiKey;

    public ProxyCheckGeoProvider(String apiKey) { this.apiKey = apiKey; }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://proxycheck.io/v2/" + ipAddress + "?key=" + apiKey + "&asn=1").build(), "ProxyCheckGeoProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<GeoResult> parse(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        JsonObject address = json.getAsJsonObject(ipAddress);
        return Optional.of(new GeoResult(ipAddress, ProviderHttp.string(address, "isocode"), "Unknown",
                ProviderHttp.string(address, "provider")));
    }
}
