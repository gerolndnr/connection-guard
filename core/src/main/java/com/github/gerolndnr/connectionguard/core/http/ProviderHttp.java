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
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.github.gerolndnr.connectionguard.core.lookup.*;

/** Shared bounded HTTP transport; provider failures never include URLs or keys in logs. */
public final class ProviderHttp {
    private static volatile OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private ProviderHttp() { }

    public static void configure(LookupSettings settings) {
        CLIENT = CLIENT.newBuilder().callTimeout(settings.httpTimeoutMillis, TimeUnit.MILLISECONDS).build();
    }

    public static <T> CompletableFuture<T> submit(Supplier<T> operation) {
        return ConnectionGuard.getLookupRuntime().submit(operation);
    }

    public static LookupException failure(RuntimeException error) {
        return error instanceof LookupException ? (LookupException) error : new LookupException(FailureReason.INVALID_RESPONSE);
    }

    public static Optional<JsonObject> readJson(Request request, String provider) {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.code() == 429) throw new LookupException(FailureReason.RATE_LIMIT, retryAfter(response));
            if (response.code() == 401 || response.code() == 403) throw new LookupException(FailureReason.AUTHENTICATION);
            if (!response.isSuccessful()) throw new LookupException(FailureReason.HTTP_ERROR);
            if (response.body() == null) throw new LookupException(FailureReason.INVALID_RESPONSE);
            if (response.body().contentLength() > 262144) throw new LookupException(FailureReason.INVALID_RESPONSE);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (body.size() + count > 262144) throw new LookupException(FailureReason.INVALID_RESPONSE);
                    body.write(buffer, 0, count);
                }
            }
            return Optional.of(JsonParser.parseString(new String(body.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (InterruptedIOException failure) {
            throw new LookupException(FailureReason.TIMEOUT);
        } catch (IOException failure) {
            throw new LookupException(FailureReason.NETWORK);
        } catch (RuntimeException failure) {
            throw failure(failure);
        }
    }

    private static long retryAfter(Response response) {
        String seconds = response.header("Retry-After", response.header("X-Ttl", "60"));
        try { return Math.min(3600000, Math.max(1000, Long.parseLong(seconds) * 1000)); }
        catch (NumberFormatException invalid) { return 60000; }
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
