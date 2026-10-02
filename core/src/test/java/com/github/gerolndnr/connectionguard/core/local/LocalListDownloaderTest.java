package com.github.gerolndnr.connectionguard.core.local;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalListDownloaderTest {
    @TempDir Path directory;
    @Test void successfulFetchDatesAndBoundedFailuresPreserveLastGoodManifestWithoutRedirects() throws Exception {
        LocalSource source = LocalDataStoreTest.source("vpn", LocalSource.Kind.VPN);
        LocalDataStore store = new LocalDataStore(directory.toRealPath(), Collections.singletonList(source));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetCalls = new AtomicInteger();
        server.createContext("/good", exchange -> {
            byte[] bytes = "192.0.2.0/24\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)));
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/target", exchange -> { targetCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/large", exchange -> { exchange.sendResponseHeaders(200, 4 * 1024 * 1024 + 1); exchange.close(); });
        server.createContext("/bad", exchange -> { byte[] body = "hostname.example\n".getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        server.createContext("/denied", exchange -> { exchange.sendResponseHeaders(429, -1); exchange.close(); });
        server.start();
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            LocalSnapshot good = LocalListDownloader.download(store, "vpn", base + "/good", client);
            assertEquals("HTTP_LAST_MODIFIED", good.timeBasis);
            for (String failure : new String[]{"/redirect", "/large", "/denied", "/bad"}) {
                assertThrows(Exception.class, () -> LocalListDownloader.download(store, "vpn", base + failure, client));
                assertEquals(good.version, store.load("vpn", System.currentTimeMillis()).version);
            }
            assertEquals(0, targetCalls.get());
        } finally { server.stop(0); }
    }
}
