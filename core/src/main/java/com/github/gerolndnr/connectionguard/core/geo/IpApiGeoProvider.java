package com.github.gerolndnr.connectionguard.core.geo;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpApiGeoProvider implements GeoProvider {
    class IpApiGeoResponse {
        String status;
        String message;
        String countryCode;
        String city;
        String isp;
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://ip-api.com/json/" + ipAddress + "?fields=status,message,countryCode,city,isp")
                    .build();
            Response response;
            IpApiGeoResponse apiGeoResponse;
            try {
                response = httpClient.newCall(request).execute();
                Gson gson = new Gson();
                apiGeoResponse = gson.fromJson(response.body().string(), IpApiGeoResponse.class);
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("IP-API | " + e.getMessage());
                return Optional.empty();
            }

            if (apiGeoResponse.status.equalsIgnoreCase("fail")) {
                ConnectionGuard.getLogger().info("IP-API | " + apiGeoResponse.message);
                return Optional.empty();
            }

            return Optional.of(
                    new GeoResult(
                            ipAddress,
                            apiGeoResponse.countryCode,
                            apiGeoResponse.city,
                            apiGeoResponse.isp
                    )
            );
        });
    }
}