package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpApiVpnProvider implements VpnProvider {


    public IpApiVpnProvider() {  }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("http://ip-api.com/json/" + ipAddress + "?fields=status,message,proxy").build(), "IpApiVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                ProviderHttp.unavailable("IpApiVpnProvider");
                return Optional.empty();
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        if (!ProviderHttp.string(json, "status").equalsIgnoreCase("success")) return Optional.empty();
        return Optional.of(new VpnResult(ipAddress, ProviderHttp.bool(json, "proxy")));
    }
}
