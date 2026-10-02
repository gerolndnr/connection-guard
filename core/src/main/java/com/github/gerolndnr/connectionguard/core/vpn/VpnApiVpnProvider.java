package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class VpnApiVpnProvider implements VpnProvider {
    private final String apiKey;

    public VpnApiVpnProvider(String apiKey) { this.apiKey = apiKey; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("https://vpnapi.io/api/" + ipAddress + "?key=" + apiKey).build(), "VpnApiVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                ProviderHttp.unavailable("VpnApiVpnProvider");
                return Optional.empty();
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        JsonObject security = json.getAsJsonObject("security");
        boolean vpn = ProviderHttp.bool(security, "vpn");
        boolean proxy = ProviderHttp.bool(security, "proxy");
        boolean tor = ProviderHttp.bool(security, "tor");
        boolean relay = ProviderHttp.bool(security, "relay");
        return Optional.of(new VpnResult(ipAddress, vpn || proxy || tor || relay));
    }
}
