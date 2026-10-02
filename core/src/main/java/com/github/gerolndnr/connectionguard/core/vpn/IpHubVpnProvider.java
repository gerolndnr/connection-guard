package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpHubVpnProvider implements VpnProvider {
    private final String apiKey;

    public IpHubVpnProvider(String apiKey) { this.apiKey = apiKey; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://v2.api.iphub.info/ip/" + ipAddress).header("X-Key", apiKey).build(), "IpHubVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                ProviderHttp.unavailable("IpHubVpnProvider");
                return Optional.empty();
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        if (json.get("block") == null || !json.get("block").isJsonPrimitive()
                || !json.get("block").getAsJsonPrimitive().isNumber()) return Optional.empty();
        double block = json.get("block").getAsDouble();
        if (block != 0 && block != 1 && block != 2) return Optional.empty();
        return Optional.of(new VpnResult(ipAddress, block == 1));
    }
}
