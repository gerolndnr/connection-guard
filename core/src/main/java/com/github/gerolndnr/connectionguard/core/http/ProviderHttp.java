package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Shared bounded HTTP transport; provider failures never include URLs or keys in logs. */
public final class ProviderHttp {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build();

    private ProviderHttp() { }

    public static Optional<JsonObject> readJson(Request request, String provider) {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                unavailable(provider);
                return Optional.empty();
            }
            return Optional.of(JsonParser.parseString(response.body().string()).getAsJsonObject());
        } catch (IOException | RuntimeException failure) {
            unavailable(provider);
            return Optional.empty();
        }
    }

    public static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid string field");
        }
        return value.getAsString();
    }

    public static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Missing or invalid boolean field");
        }
        return value.getAsBoolean();
    }

    public static void unavailable(String provider) {
        if (ConnectionGuard.getLogger() != null) {
            ConnectionGuard.getLogger().warning(provider + " response unavailable; check provider settings and quota.");
        }
    }
}
