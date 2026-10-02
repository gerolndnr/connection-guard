package com.github.gerolndnr.connectionguard.core.local;

import okhttp3.*;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** One bounded HTTPS GET; no redirects, decompressed bodies are counted, no credentials or response in errors. */
public final class LocalListDownloader {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().callTimeout(10000, TimeUnit.MILLISECONDS)
            .connectTimeout(3000, TimeUnit.MILLISECONDS).readTimeout(3000, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    private LocalListDownloader() { }
    public static LocalSnapshot update(LocalDataStore store, String id) throws IOException {
        LocalSource source = store.source(id);
        if (source.downloadUrl().isEmpty() || source.isDatabase()) throw new IllegalArgumentException("This source has no public HTTPS list download configured.");
        return download(store, id, source.downloadUrl(), CLIENT);
    }
    // Package-private transport injection for a loopback fixture; production URLs are checked by LocalSource.
    static LocalSnapshot download(LocalDataStore store, String id, String url, OkHttpClient client) throws IOException {
        try (Response response = client.newCall(new Request.Builder().url(url).header("User-Agent", "ConnectionGuard-local-data").build()).execute()) {
            if (!response.isSuccessful() || response.body() == null || response.body().contentLength() > 4 * 1024 * 1024) throw new IOException("Local list download failed (endpoint/body redacted).");
            byte[] bytes = LocalDataStore.readBounded(response.body().byteStream(), 4 * 1024 * 1024);
            long now = System.currentTimeMillis(), asOf = now; String basis = "FETCH";
            if (response.header("Last-Modified") != null) {
                try { asOf = ZonedDateTime.parse(response.header("Last-Modified"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); basis = "HTTP_LAST_MODIFIED"; }
                catch (RuntimeException invalid) { throw new IOException("Local list has an invalid Last-Modified date (value redacted)."); }
            }
            return store.downloaded(id, bytes, asOf, basis, now);
        } catch (IOException failure) { throw new IOException("Local list update failed; previous manifest preserved (details redacted)."); }
    }
}
