package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.http.AnnouncedLimits;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A join wave like a third-party benchmark's: 1,000 logins within 20 seconds, 200 of them bad, against
 * loopback services with their measured latency (zowi 240 ms and 250 a minute, Blackbox 90 ms, IPQuery
 * 330 ms). Every service calls the bad addresses VPN, so each checked bad address is caught.
 * Takes about 75 seconds; runs only with CG_JOIN_WAVE=1.
 */
@Timeout(180)
class JoinWaveSimulationTest {
    private static final int PLAYERS = 1000, BAD = 200;
    private static final long SPREAD_MS = 20000;
    private HttpServer server;
    private ExecutorService handlers;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Deque<Long> zowiWindow = new ArrayDeque<>();

    @BeforeAll static void enabled() { Assumptions.assumeTrue("1".equals(System.getenv("CG_JOIN_WAVE")), "CG_JOIN_WAVE=1 runs the wave"); }

    private static boolean bad(String path) { return path.contains("198.51.100."); }

    @BeforeEach void setup() throws Exception {
        ConnectionGuard.setLogger(null); ConnectionGuard.setCacheProvider(new NoCacheProvider()); AnnouncedLimits.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
        handlers = Executors.newCachedThreadPool(); server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(), id = path.split("/")[1];
            calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            int code = 200; String body;
            try {
                if (id.equals("zowi")) {
                    Thread.sleep(240);
                    synchronized (zowiWindow) {
                        long now = System.currentTimeMillis();
                        while (!zowiWindow.isEmpty() && now - zowiWindow.peekFirst() > 60000) zowiWindow.pollFirst();
                        if (zowiWindow.size() >= 250) code = 429; else zowiWindow.addLast(now);
                    }
                    exchange.getResponseHeaders().add("X-RateLimit-Limit", "250");
                    if (code == 429) exchange.getResponseHeaders().add("Retry-After", "60");
                    body = "{\"security\":{\"vpn\":{\"detected\":" + bad(path) + "},\"proxy\":false,\"tor\":false,\"hosting\":" + bad(path) + "}}";
                } else if (id.equals("blackbox")) {
                    Thread.sleep(90); body = bad(path) ? "Y" : "N";
                } else {
                    // Optional: one IPQuery answer in 20 slower than the 1,500 ms HTTP timeout.
                    Thread.sleep(slowTail && calls.get(id).get() % 20 == 0 ? 1700 : 330);
                    body = "{\"risk\":{\"is_vpn\":" + bad(path) + ",\"is_proxy\":false,\"is_tor\":false,\"is_datacenter\":" + bad(path) + "}}";
                }
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, data.length); exchange.getResponseBody().write(data);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException closed) { /* The client gave up. */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() throws Exception {
        server.stop(0); handlers.shutdownNow();
        Map<String, Object> empty = new HashMap<>(); empty.put("provider.geo.service", "Disabled");
        ConnectionGuard.applyProviders(new ProviderConfiguration(empty::get, Collections.emptyList()));
        AnnouncedLimits.clear();
    }

    private Map<String, Object> config(boolean before) {
        Map<String, Object> values = new HashMap<>(); values.put("provider.geo.service", "Disabled");
        for (String id : Arrays.asList("zowi", "blackbox", "ipquery")) values.put("provider.vpn." + id + ".enabled", true);
        values.put("provider.vpn-failover.order", Arrays.asList("zowi", "blackbox", "ipquery"));
        values.put("provider.vpn.blackbox.require-confirmation", true);
        if (before) {
            values.put("lookup.workers", 8); values.put("lookup.queue-capacity", 64); values.put("lookup.max-inflight", 128);
            values.put("provider.vpn.zowi.minute-budget", 60);
        } else values.put("provider.vpn.zowi.minute-budget", "auto");
        return values;
    }

    private volatile boolean slowTail;

    private String wave(boolean before) throws Exception {
        // Provider health survives a reload for still-selected sources; start each wave from none.
        Map<String, Object> none = new HashMap<>(); none.put("provider.geo.service", "Disabled");
        ConnectionGuard.applyProviders(new ProviderConfiguration(none::get, Collections.emptyList())); AnnouncedLimits.clear();
        Map<String, Object> values = config(before);
        ProviderConfiguration draft = new ProviderConfiguration(values::get, Arrays.asList("zowi", "blackbox", "ipquery"));
        for (int i = 0; i < draft.providers.size(); i++) {
            String id = draft.keys.get(i); HttpUrl endpoint = HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/" + id);
            draft.providers.set(i, id.equals("blackbox") ? new BlackboxVpnProvider(true, draft.intelSnapshot, endpoint)
                    : id.equals("ipquery") ? new IpQueryVpnProvider(HttpUrl.get(endpoint + "/")) : new ZowiVpnProvider(endpoint));
        }
        ConnectionGuard.applyProviders(draft); calls.clear(); synchronized (zowiWindow) { zowiWindow.clear(); }
        List<String> addresses = new ArrayList<>();
        for (int i = 1; i <= BAD; i++) addresses.add("198.51.100." + i);
        for (int i = 1; i <= PLAYERS - BAD; i++) addresses.add("2001:db8::" + Integer.toHexString(i));
        Collections.shuffle(addresses, new Random(7));
        ScheduledExecutorService clock = Executors.newScheduledThreadPool(4);
        List<CompletableFuture<long[]>> joins = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            String ip = addresses.get(i); CompletableFuture<long[]> done = new CompletableFuture<>(); joins.add(done);
            clock.schedule(() -> {
                long start = System.nanoTime();
                LoginChecks.check(ip, ConnectionGuard.getLookupRuntime().getSettings(), start, false,
                        LoginChecks.Permission.known(false), LoginChecks.Permission.known(true)).whenComplete((result, error) -> {
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    boolean refused = error == null && result.vpn().isVpn();
                    boolean unchecked = error != null || result.vpn().getStatus() == ProviderVote.Status.UNKNOWN;
                    done.complete(new long[]{bad("/" + ip) ? 1 : 0, refused ? 1 : 0, unchecked ? 1 : 0, ms});
                });
            }, i * SPREAD_MS / PLAYERS, TimeUnit.MILLISECONDS);
        }
        int badRefused = 0, goodRefused = 0, unchecked = 0; List<Long> times = new ArrayList<>();
        for (CompletableFuture<long[]> join : joins) {
            long[] r = join.get(60, TimeUnit.SECONDS); times.add(r[3]); unchecked += r[2];
            if (r[0] == 1 && r[1] == 1) badRefused++; if (r[0] == 0 && r[1] == 1) goodRefused++;
        }
        clock.shutdownNow(); Collections.sort(times);
        String line = (before ? "before" : "after ") + (slowTail ? " slow tail" : "") + ": bad refused " + badRefused + "/" + BAD + ", good refused " + goodRefused + "/" + (PLAYERS - BAD)
                + ", unchecked " + unchecked + ", median " + times.get(times.size() / 2) + " ms, p95 " + times.get(times.size() * 95 / 100)
                + " ms, calls " + new TreeMap<>(calls);
        System.out.println("JOIN WAVE " + line);
        long due = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!ConnectionGuard.getLookupRuntime().isIdle() || LoginChecks.active() != 0) && System.nanoTime() < due) Thread.sleep(5);
        return line + "|" + badRefused + "|" + goodRefused;
    }

    @Test void newDefaultsCheckTheWave() throws Exception {
        String[] before = wave(true).split("\\|");
        String[] after = wave(false).split("\\|");
        // One IPQuery answer in 20 over the HTTP timeout: before 0.6.2 this opened its circuit for 30 s.
        slowTail = true; String[] tail = wave(false).split("\\|"); slowTail = false;
        assertTrue(Integer.parseInt(tail[1]) >= 180, tail[0]);
        assertEquals("0", after[2], after[0]);
        assertTrue(Integer.parseInt(after[1]) >= 190, after[0]);
        assertTrue(Integer.parseInt(after[1]) > Integer.parseInt(before[1]), before[0] + " / " + after[0]);
    }
}
