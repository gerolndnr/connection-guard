package com.github.gerolndnr.connectionguard.core.geo;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpApiGeoProvider implements GeoProvider {


    public IpApiGeoProvider() {  }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("http://ip-api.com/json/" + ipAddress + "?fields=status,message,countryCode,city,isp,as").build(), "IpApiGeoProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<GeoResult> parse(String ipAddress, JsonObject json) {
        if (!ProviderHttp.string(json, "status").equalsIgnoreCase("success")) return Optional.empty();
        DetectionFields.address(json, "query", ipAddress);
        GeoResult result = new GeoResult(ipAddress, ProviderHttp.string(json, "countryCode"),
                ProviderHttp.string(json, "city"), ProviderHttp.string(json, "isp"));
        result.setAsn(DetectionFields.asn(json, "as", true));
        return Optional.of(result);
    }
}
