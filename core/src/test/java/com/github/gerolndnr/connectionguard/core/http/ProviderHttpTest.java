package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.custom.CustomVpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Request;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ProviderHttpTest {
    private HttpServer server;
    private String url;
    private String body = "{\"data\":{\"isVpn\":true,\"vpnProvider\":\"Local Test VPN\"}}";
    private int status = 200;
    private AtomicReference<String> header = new AtomicReference<>();
    private AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        ConnectionGuard.setLogger(logger);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Test"));
            byte[] bytes = new byte[256];
            int count = exchange.getRequestBody().read(bytes);
            requestBody.set(count < 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8));
            bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/%IP%";
    }
    @AfterEach void stop() { server.stop(0); }

    private CustomVpnProvider provider(String method, String flag, String type, String name) {
        return new CustomVpnProvider(method, url, Collections.singletonList("X-Test: scheme:part-%IP%"),
                "application/json", "{\"ip\":\"%IP%\"}", "application/json", flag, type, "yes", name);
    }
    private Optional<VpnResult> query(CustomVpnProvider provider) throws Exception {
        return provider.getVpnResult("192.0.2.1").get(3, TimeUnit.SECONDS);
    }
    @Test void readsBothDocumentedNestedFields() throws Exception {
        VpnResult result = query(provider("GET", "data#isVpn", "BOOLEAN", "data#vpnProvider")).get();
        assertTrue(result.isVpn());
        assertEquals("Local Test VPN", result.getVpnProviderName().get());
    }
    @Test void readsAnIpv4ObjectKeyLiterally() throws Exception {
        body = "{\"192.0.2.1\":{\"vpn\":\"YES\",\"name\":\"Test\"}}";
        assertTrue(query(provider("GET", "%IP%#vpn", "STRING", "%IP%#name")).get().isVpn());
    }
    @Test void sendsPostBodyAndKeepsColonsInHeaderValues() throws Exception {
        assertTrue(query(provider("POST", "data#isVpn", "BOOLEAN", "")).get().isVpn());
        assertEquals("scheme:part-192.0.2.1", header.get());
        assertEquals("{\"ip\":\"192.0.2.1\"}", requestBody.get());
    }
    @ParameterizedTest @ValueSource(ints={401,429,500,503})
    void httpErrorsAreUnavailableEvenWithAValidLookingBody(int code) throws Exception {
        status = code;
        assertFalse(query(provider("GET", "data#isVpn", "BOOLEAN", "")).isPresent());
    }
    @ParameterizedTest @ValueSource(strings={"not JSON", "[]", "{}", "{\"data\":null}",
            "{\"data\":{\"isVpn\":\"false\"}}", "{\"data\":{\"isVpn\":null}}"})
    void missingOrMalformedFlagsNeverBecomeNegativeVotes(String response) throws Exception {
        body = response;
        assertFalse(query(provider("GET", "data#isVpn", "BOOLEAN", "")).isPresent());
    }
    @Test void readsValidJsonThroughSharedTransport() {
        assertTrue(ProviderHttp.readJson(new Request.Builder().url(url.replace("%IP%", "192.0.2.1")).build(), "Test").isPresent());
    }
}
