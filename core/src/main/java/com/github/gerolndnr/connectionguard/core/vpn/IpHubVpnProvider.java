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

public class IpHubVpnProvider implements VpnProvider {
    private String apiKey;

    public IpHubVpnProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://v2.api.iphub.info/ip/" + ipAddress)
                    .header("X-Key", apiKey)
                    .build();
            Response response;

            int blockLevel;
            JsonObject jsonObject;
            try {
                response = httpClient.newCall(request).execute();
                jsonObject = JsonParser.parseString(response.body().string()).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("IP-API | " + e.getMessage());
                return Optional.empty();
            }

            if (response.code() != 200) {
                ConnectionGuard.getLogger().info("IP-Hub | API returned with status code " + response.code());
                return Optional.empty();
            }

            blockLevel = jsonObject.get("block").getAsInt();

            if (blockLevel == 1) {
                return Optional.of(new VpnResult(ipAddress, true));
            } else {
                return Optional.of(new VpnResult(ipAddress, false));
            }
        });
    }
}
