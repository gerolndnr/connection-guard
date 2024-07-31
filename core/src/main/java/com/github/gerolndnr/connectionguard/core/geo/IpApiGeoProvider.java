package com.github.gerolndnr.connectionguard.core.geo;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpApiGeoProvider implements GeoProvider {
    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://ip-api.com/json/" + ipAddress + "?fields=status,message,countryCode,city,isp")
                    .build();
            Response response;

            String status;
            String message;
            String countryCode;
            String cityName;
            String ispName;
            JsonObject jsonObject;
            try {
                response = httpClient.newCall(request).execute();
                jsonObject = JsonParser.parseString(response.body().string()).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("IP-API | " + e.getMessage());
                return Optional.empty();
            }

            status = jsonObject.get("status").getAsString();
            countryCode = jsonObject.get("countryCode").getAsString();
            cityName = jsonObject.get("city").getAsString();
            ispName = jsonObject.get("isp").getAsString();

            if (status.equalsIgnoreCase("fail")) {
                message = jsonObject.get("message").getAsString();
                ConnectionGuard.getLogger().info("IP-API | " + message);
                return Optional.empty();
            }

            return Optional.of(
                    new GeoResult(
                            ipAddress,
                            countryCode,
                            cityName,
                            ispName
                    )
            );
        });
    }
}