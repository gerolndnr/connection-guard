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
        if (!(error instanceof LookupException)) com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(error, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.LOOKUP);
        return error instanceof LookupException ? (LookupException) error : new LookupException(FailureReason.INVALID_RESPONSE);
    }

    public static Optional<JsonObject> readJson(Request request, String provider) {
        try { return Optional.of(JsonParser.parseString(readText(request, provider)).getAsJsonObject()); }
        catch (RuntimeException invalid) { throw failure(invalid); }
    }

    /** Same bounded transport/status handling for native plain-text services. */
    public static String readText(Request request, String provider) {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.code() == 429) {
                long retry = retryAfter(response); boolean quota = false;
                try {
                    if (response.body() != null) {
                        JsonObject denied = JsonParser.parseString(new String(readBody(response), StandardCharsets.UTF_8)).getAsJsonObject();
                        quota = exhaustedDailyQuota(denied);
                    }
                } catch (RuntimeException | IOException malformed) { /* Keep the explicit HTTP rate-limit fact. */ }
                throw new LookupException(quota ? FailureReason.BUDGET_EXHAUSTED : FailureReason.RATE_LIMIT, quota ? Math.max(retry, untilNextDay()) : retry);
            }
            if (response.code() == 401 || response.code() == 403) throw new LookupException(FailureReason.AUTHENTICATION);
            if (!response.isSuccessful()) throw new LookupException(FailureReason.HTTP_ERROR);
            if (response.body() == null) throw new LookupException(FailureReason.INVALID_RESPONSE);
            if (response.body().contentLength() > 262144) throw new LookupException(FailureReason.INVALID_RESPONSE);
            return new String(readBody(response), StandardCharsets.UTF_8);
        } catch (InterruptedIOException failure) {
            com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.LOOKUP);
            throw new LookupException(FailureReason.TIMEOUT);
        } catch (IOException failure) {
            com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.LOOKUP);
            throw new LookupException(FailureReason.NETWORK);
        } catch (RuntimeException failure) {
            throw failure(failure);
        }
    }

    private static byte[] readBody(Response response) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream input = response.body().byteStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (body.size() + count > 262144) throw new LookupException(FailureReason.INVALID_RESPONSE);
                body.write(buffer, 0, count);
            }
        }
        return body.toByteArray();
    }
    /** Recognized quota denial only; never guess exhausted quota from a generic 429. */
    public static boolean exhaustedDailyQuota(JsonObject json) {
        com.google.gson.JsonElement field = json.get("message");
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) return false;
        String message = field.getAsString().toLowerCase(java.util.Locale.ROOT);
        return message.contains("queries exhausted") || message.contains("daily query allowance") && (message.contains("exceeded") || message.contains("exhausted"));
    }
    public static long untilNextDay() {
        return Math.max(1000, java.time.Duration.between(java.time.Instant.now(), java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()).toMillis());
    }

    private static long retryAfter(Response response) {
        String seconds = response.header("Retry-After", response.header("X-Ttl", "60"));
        try { return Math.min(86400000, Math.max(1000, Math.multiplyExact(Long.parseLong(seconds), 1000))); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return 60000; }
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
