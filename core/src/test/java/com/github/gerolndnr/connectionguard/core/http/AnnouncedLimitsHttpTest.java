package com.github.gerolndnr.connectionguard.core.http;

import com.sun.net.httpserver.HttpServer;
import okhttp3.Request;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class AnnouncedLimitsHttpTest {
    private HttpServer server;
    private volatile String limit;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            if (limit != null) exchange.getResponseHeaders().add("X-RateLimit-Limit", limit);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); AnnouncedLimits.clear(); }

    private void call() {
        ProviderHttp.readText(new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/").build(), "fixture");
    }

    @Test void recordsTheAnnouncedLimitAndKeepsTheLastValidOne() {
        call(); assertEquals(0, AnnouncedLimits.get("fixture"));
        limit = "250"; call(); assertEquals(250, AnnouncedLimits.get("fixture"));
        limit = "not-a-number"; call(); assertEquals(250, AnnouncedLimits.get("fixture"));
        limit = "0"; call(); assertEquals(250, AnnouncedLimits.get("fixture"));
        assertEquals(0, AnnouncedLimits.get("other"));
    }
}
