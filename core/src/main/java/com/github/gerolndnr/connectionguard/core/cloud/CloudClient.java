package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSink;
import okio.Okio;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** HTTP for the cloud only: own client, no redirects, short timeouts, bounded responses. */
final class CloudClient {
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final long MAX_RESPONSE = 64 * 1024;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            // Retrying a POST is safe here: the server ignores a sync whose seq it already counted.
            // It also recovers from pooled keep-alive connections the server closed in the meantime.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true).build();
    private final URI endpoint;
    private final String userAgent;

    CloudClient(URI endpoint, String pluginVersion) {
        this.endpoint = endpoint;
        this.userAgent = "ConnectionGuard/" + pluginVersion.replaceAll("[^A-Za-z0-9._-]", "");
    }

    static final class Reply {
        final int status;
        final JsonObject body;
        Reply(int status, JsonObject body) { this.status = status; this.body = body; }
        boolean ok() { return status >= 200 && status < 300 && body != null; }
        /** Server-requested retry delay in seconds, or -1. */
        int retryIn() {
            if (body == null || !body.has("retry_in") || body.get("retry_in").isJsonNull()) return -1;
            return body.get("retry_in").getAsInt();
        }
    }

    Reply post(String path, JsonObject payload, String authorization) throws IOException {
        Buffer gz = new Buffer();
        try (okio.BufferedSink sink = Okio.buffer(new GzipSink(gz))) { sink.writeUtf8(GSON.toJson(payload)); }
        Request.Builder request = new Request.Builder()
                .url(endpoint.toString() + path)
                .header("User-Agent", userAgent)
                .header("Content-Encoding", "gzip")
                .post(RequestBody.create(gz.readByteString(), JSON));
        if (authorization != null) request.header("Authorization", authorization);
        try (Response response = http.newCall(request.build()).execute()) {
            ResponseBody body = response.body();
            JsonObject json = null;
            if (body != null) {
                BufferedSource source = body.source();
                if (source.request(MAX_RESPONSE + 1)) throw new IOException("Cloud response too large.");
                String text = source.getBuffer().readUtf8();
                try { json = GSON.fromJson(text, JsonObject.class); } catch (RuntimeException notJson) { json = null; }
            }
            return new Reply(response.code(), json);
        }
    }

    void close() { http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll(); }
}
