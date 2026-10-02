package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

public class CGWebHookHelper {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(2500, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "ConnectionGuard-webhooks"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    public static CompletableFuture<Void> sendWebHook(String url, String content) {
        try { return CompletableFuture.runAsync(() -> {
            try {
            Gson gson = new Gson();
            String jsonRequest = gson.toJson(new CGWebHookRequest(content));

            RequestBody requestBody = RequestBody.create(jsonRequest, MediaType.get("application/json"));

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) unavailable();
            }
            } catch (IOException | RuntimeException failure) { unavailable(); }
        }, EXECUTOR); }
        catch (RejectedExecutionException full) { return CompletableFuture.completedFuture(null); }
    }
    private static void unavailable() {
        if (ConnectionGuard.getLogger() != null) ConnectionGuard.getLogger().warning("Webhook unavailable; check URL and delivery settings (details redacted).");
    }
    public static void shutdown() { EXECUTOR.shutdownNow(); CLIENT.dispatcher().cancelAll(); }
}
