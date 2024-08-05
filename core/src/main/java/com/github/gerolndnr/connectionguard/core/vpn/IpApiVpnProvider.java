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

public class IpApiVpnProvider implements VpnProvider {
    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://ip-api.com/json/" + ipAddress + "?fields=proxy")
                    .build();
            Response response;

            String status;
            String message;
            boolean isProxy;
            JsonObject jsonObject;
            try {
                response = httpClient.newCall(request).execute();
                jsonObject = JsonParser.parseString(response.body().string()).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("IP-API | " + e.getMessage());
                return Optional.empty();
            }

            isProxy = jsonObject.get("proxy").getAsBoolean();

            if (isProxy) {
                return Optional.of(new VpnResult(ipAddress, true));
            } else {
                return Optional.of(new VpnResult(ipAddress, false));
            }
        });
    }
}
