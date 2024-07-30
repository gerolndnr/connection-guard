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

public class ProxyCheckVpnProvider implements VpnProvider {
    private String apiKey;

    public ProxyCheckVpnProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();

            Request request = new Request.Builder()
                    .url(
                            "http://proxycheck.io/v2/"
                            + ipAddress
                            + "?key=" + apiKey
                            + "&vpn=1"
                    ).build();

            Response response;
            try {
                response = httpClient.newCall(request).execute();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info(
                        "Could not execute GET request on proxycheck vpn provider."
                );
                return Optional.empty();
            }

            JsonObject jsonObject;
            try {
                jsonObject = JsonParser.parseString(response.body().string()).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info(
                        "Could not turn response body of proxycheck vpn provider into a string."
                );
                return Optional.empty();
            }
            String requestStatus = jsonObject.get("status").getAsString();

            switch (requestStatus.toLowerCase()) {
                case "ok":
                    break;
                case "warning":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                            + jsonObject.get("message").getAsString()
                    );
                    break;
                case "denied":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                            + jsonObject.get("message").getAsString()
                    );
                    return Optional.empty();
                case "error":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                            + jsonObject.get("message").getAsString()
                    );
                    return Optional.empty();
            }

            String isVpn = jsonObject.get(ipAddress).getAsJsonObject().get("proxy").getAsString();

            if (isVpn.equalsIgnoreCase("yes")) {
                return Optional.of(new VpnResult(ipAddress, true));
            } else {
                return Optional.of(new VpnResult(ipAddress, false));
            }
        });
    }
}
