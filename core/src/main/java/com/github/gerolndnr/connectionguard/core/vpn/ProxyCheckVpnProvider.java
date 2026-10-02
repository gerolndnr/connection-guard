package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckVpnProvider implements VpnProvider {
    private final String apiKey;

    public ProxyCheckVpnProvider(String apiKey) { this.apiKey = apiKey; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://proxycheck.io/v2/" + ipAddress + "?key=" + apiKey + "&vpn=1").build(), "ProxyCheckVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) return Optional.empty();
        String proxy = ProviderHttp.string(json.getAsJsonObject(ipAddress), "proxy");
        if (!proxy.equalsIgnoreCase("yes") && !proxy.equalsIgnoreCase("no")) return Optional.empty();
        return Optional.of(new VpnResult(ipAddress, proxy.equalsIgnoreCase("yes")));
    }
}
