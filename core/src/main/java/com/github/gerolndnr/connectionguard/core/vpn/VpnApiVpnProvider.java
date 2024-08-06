package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class VpnApiVpnProvider implements VpnProvider {
    private String apiKey;

    public VpnApiVpnProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("https://vpnapi.io/api/" + ipAddress + "?key=" + apiKey)
                    .build();
            Response response;

            JsonObject jsonObject;
            try {
                response = httpClient.newCall(request).execute();
                jsonObject = JsonParser.parseString(response.body().string()).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("VPNAPI | " + e.getMessage());
                return Optional.empty();
            }

            try {
                boolean isVpn = jsonObject.get("security").getAsJsonObject().get("vpn").getAsBoolean();
                boolean isProxy = jsonObject.get("security").getAsJsonObject().get("proxy").getAsBoolean();
                boolean isTor = jsonObject.get("security").getAsJsonObject().get("tor").getAsBoolean();
                boolean isRelay = jsonObject.get("security").getAsJsonObject().get("relay").getAsBoolean();

                if (isVpn || isProxy || isTor || isRelay) {
                    return Optional.of(new VpnResult(ipAddress, true));
                } else {
                    return Optional.of(new VpnResult(ipAddress, false));
                }
            } catch (Exception e) {
                ConnectionGuard.getLogger().info("VPNAPI | " + e.getMessage());
                return Optional.empty();
            }
        });
    }
}
