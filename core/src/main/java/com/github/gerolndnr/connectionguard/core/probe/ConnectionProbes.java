package com.github.gerolndnr.connectionguard.core.probe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Experimental, observe-only connection probe (proof of concept).
 *
 * <ul>
 *   <li><b>Ping gap:</b> the kernel measures the round trip only to the TCP peer. A SOCKS, residential or
 *   booster proxy ends that TCP connection itself, while the game's keep-alive ping still travels on to the
 *   player. A ping far above the kernel round trip therefore means a TCP-terminating proxy.</li>
 *   <li><b>Segment size:</b> a tunnel lowers the MTU, and the player's SYN announces the smaller MSS
 *   (WireGuard's default 1420-byte MTU gives 1380). Known access technologies are named, because DS-Lite
 *   and PPPoE also lower it.</li>
 * </ul>
 *
 * No address is kept: a probe is keyed by account and ends with the session.
 */
public final class ConnectionProbes {
    public enum Mss { STANDARD, PPPOE, DS_LITE, WIREGUARD, TUNNEL, UNKNOWN }
    public static final int MAX_PINGS = 12;
    private static final long PENDING_MILLIS = 60_000;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Probe> probes = new ConcurrentHashMap<>();
    private final Map<Mss, AtomicLong> mssCounts = new EnumMap<>(Mss.class);
    private final AtomicLong measured = new AtomicLong(), suspected = new AtomicLong(), unavailable = new AtomicLong(), behindProxy = new AtomicLong();
    private final long thresholdMillis;
    private final Consumer<String> log;

    public ConnectionProbes(long thresholdMillis, Consumer<String> log) {
        this.thresholdMillis = thresholdMillis; this.log = log;
        for (Mss mss : Mss.values()) mssCounts.put(mss, new AtomicLong());
    }

    /** IPv4 and IPv6 headers differ by 20 bytes, so each family has its own table. */
    public static Mss classify(long peerMss, boolean ipv6) {
        long v4 = ipv6 ? peerMss + 20 : peerMss;
        if (peerMss <= 0) return Mss.UNKNOWN;
        if (v4 == 1460) return Mss.STANDARD;
        if (v4 == 1452) return Mss.PPPOE;
        if (v4 == 1420 && !ipv6) return Mss.DS_LITE;
        if (v4 == 1380) return Mss.WIREGUARD;
        return v4 <= 1420 ? Mss.TUNNEL : Mss.UNKNOWN;
    }

    public static final class Assessment {
        public final Mss mss; public final long peerMss; public final double tcpRttMillis; public final long minPingMillis, gapMillis;
        public final int pings; public final boolean proxySuspected;
        Assessment(Mss mss, long peerMss, double tcpRttMillis, long minPingMillis, long gapMillis, int pings, boolean proxySuspected) {
            this.mss = mss; this.peerMss = peerMss; this.tcpRttMillis = tcpRttMillis; this.minPingMillis = minPingMillis;
            this.gapMillis = gapMillis; this.pings = pings; this.proxySuspected = proxySuspected;
        }
        public String describe() {
            return "peerMSS=" + peerMss + " (" + mss.name().toLowerCase(Locale.ROOT).replace('_', '-') + ") tcpRTT="
                    + String.format(Locale.ROOT, "%.1f", tcpRttMillis) + "ms"
                    + (pings == 0 ? " ping=pending" : " minPing=" + minPingMillis + "ms gap=" + gapMillis + "ms pings=" + pings)
                    + (proxySuspected ? " -> TCP-terminating proxy suspected" : "");
        }
    }

    /** The smallest ping is the one least delayed by the client's frame loop; at least two are needed. */
    public static Assessment assess(TcpSample sample, List<Long> pings, boolean ipv6, long thresholdMillis) {
        long min = pings.stream().filter(p -> p >= 0).mapToLong(Long::longValue).min().orElse(-1);
        int count = (int) pings.stream().filter(p -> p >= 0).count();
        long gap = min < 0 ? 0 : Math.round(min - sample.rttMillis());
        return new Assessment(classify(sample.peerMss(), ipv6), sample.peerMss(), sample.rttMillis(), min, gap, count,
                count >= 2 && gap >= thresholdMillis);
    }

    /** Before login: the handshake already carries the segment size and a first round trip. */
    public Optional<TcpSample> preLogin(String socketKey, Object channel) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(entry -> now - entry.at > PENDING_MILLIS);
        Optional<TcpSample> sample = TcpSample.read(channel);
        sample.ifPresent(value -> pending.put(socketKey, new Pending(value, now)));
        return sample;
    }

    /** behindTcpProxy: the socket peer is not the player (HAProxy/TCPShield in front, or a backend behind a proxy). */
    public void joined(UUID uuid, String name, String socketKey, boolean ipv6, boolean behindTcpProxy, Object channel) {
        Pending before = pending.remove(socketKey);
        if (behindTcpProxy) { behindProxy.incrementAndGet(); return; }
        Optional<TcpSample> sample = TcpSample.read(channel);
        if (!sample.isPresent()) { unavailable.incrementAndGet(); return; }
        probes.put(uuid, new Probe(name, ipv6, before == null ? sample.get() : before.sample, sample.get()));
    }

    /** Called on a timer; the final call logs one line and counts the result. */
    public void sample(UUID uuid, long pingMillis, Object channel, boolean last) {
        Probe probe = probes.get(uuid);
        if (probe == null) return;
        synchronized (probe) {
            if (pingMillis >= 0 && probe.pings.size() < MAX_PINGS) probe.pings.add(pingMillis);
            TcpSample.read(channel).ifPresent(sample -> probe.latest = sample);
            if (!last || probe.done) return;
            probe.done = true;
        }
        Assessment result = probe.assessment(thresholdMillis);
        measured.incrementAndGet(); mssCounts.get(result.mss).incrementAndGet();
        if (result.proxySuspected) suspected.incrementAndGet();
        log.accept("Connection probe " + probe.name + ": " + result.describe());
    }

    public void left(UUID uuid) { probes.remove(uuid); }

    public Optional<Assessment> assessment(UUID uuid) {
        Probe probe = probes.get(uuid);
        return probe == null ? Optional.empty() : Optional.of(probe.assessment(thresholdMillis));
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        StringBuilder mss = new StringBuilder();
        mssCounts.forEach((kind, count) -> { if (count.get() > 0) mss.append(' ').append(kind.name().toLowerCase(Locale.ROOT)).append('=').append(count.get()); });
        lines.add("Connection probe (experimental, observe only): measured=" + measured.get() + " proxy-suspected=" + suspected.get()
                + " no-tcp-info=" + unavailable.get() + " behind-tcp-proxy=" + behindProxy.get() + " threshold=" + thresholdMillis + "ms");
        if (mss.length() > 0) lines.add("Segment sizes:" + mss);
        probes.values().stream().sorted(Comparator.comparing(probe -> probe.name)).limit(20)
                .forEach(probe -> lines.add(probe.name + ": " + probe.assessment(thresholdMillis).describe()));
        return lines;
    }

    private static final class Pending {
        final TcpSample sample; final long at;
        Pending(TcpSample sample, long at) { this.sample = sample; this.at = at; }
    }
    private static final class Probe {
        final String name; final boolean ipv6; final TcpSample login; final List<Long> pings = new ArrayList<>();
        TcpSample latest; boolean done;
        Probe(String name, boolean ipv6, TcpSample login, TcpSample latest) { this.name = name; this.ipv6 = ipv6; this.login = login; this.latest = latest; }
        synchronized Assessment assessment(long threshold) {
            Assessment now = assess(latest, pings, ipv6, threshold);
            // The segment size comes from the handshake; later samples may reflect path MTU discovery instead.
            return new Assessment(classify(login.peerMss(), ipv6), login.peerMss(), now.tcpRttMillis, now.minPingMillis, now.gapMillis,
                    now.pings, now.proxySuspected);
        }
    }
}
