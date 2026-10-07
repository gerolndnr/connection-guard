package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real transport timeout, open-circuit skips and one successful recovery; all HTTP is owned loopback. */
class ProviderTimeoutRuntimeTest {
    private static void idle() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
    }
    private static VpnResult query(String ip) throws Exception { return ConnectionGuard.getVpnResult(ip).get(3, TimeUnit.SECONDS); }
    @Test @Timeout(12) void hangingHostIsContactedOnceThenSkippedForTenLoginsAndRecoversAfterProbe() throws Exception {
        idle(); ConnectionGuard.setLogger(null); ConnectionGuard.setCacheProvider(new NoCacheProvider());
        CountDownLatch release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService http = Executors.newCachedThreadPool(); server.setExecutor(http);
        server.createContext("/", exchange -> {
            int n = calls.incrementAndGet();
            try {
                if (n == 1) release.await(); // No headers or body arrive before the transport's own timeout.
                byte[] answer = "Y".getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(200, answer.length); exchange.getResponseBody().write(answer);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* The first client has timed out. */ }
            finally { exchange.close(); }
        }); server.start();
        try {
            Map<String,Object> v = new HashMap<>(); v.put("provider.geo.service", "Disabled");
            v.put("provider.vpn.blackbox.enabled", true); v.put("provider.vpn.blackbox.require-confirmation", false); v.put("lookup.circuit.pause-ms", 600);
            ProviderConfiguration draft = new ProviderConfiguration(v::get, Collections.singletonList("blackbox"));
            draft.providers.set(0, new BlackboxVpnProvider(false,draft.intelSnapshot,okhttp3.HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/blackbox")));
            ConnectionGuard.applyProviders(draft);
            long start = System.nanoTime(); VpnResult first = query("192.0.2.220");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertEquals(ProviderVote.Status.UNKNOWN, first.getStatus());
            assertEquals(FailureReason.TIMEOUT, first.getVotes().get(0).getReason());
            assertTrue(ms >= 1200 && ms < 2000, "Default 1500ms call timeout; observed " + ms);
            for (int i=0; i<10; i++) {
                start = System.nanoTime(); VpnResult skipped = query("198.51.100." + (i+1));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 300);
                assertEquals(FailureReason.CIRCUIT_OPEN, skipped.getVotes().get(0).getReason());
            }
            assertEquals(1, calls.get()); release.countDown();
            Thread.sleep(650); // Explicitly reduced owned-fixture cooldown; production pause remains 30s.
            assertTrue(query("192.0.2.221").isVpn()); assertEquals(2, calls.get());
            assertTrue(query("192.0.2.222").isVpn()); assertEquals(3, calls.get());
        } finally {
            release.countDown(); server.stop(0); http.shutdownNow(); assertTrue(http.awaitTermination(3, TimeUnit.SECONDS));
            idle(); ConnectionGuard.applyProviders(new ProviderConfiguration(k -> null, Collections.emptyList()));
        }
    }
}
