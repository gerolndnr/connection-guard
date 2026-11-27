package com.github.gerolndnr.connectionguard.core.probe;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObserver;
import com.github.gerolndnr.connectionguard.api.v1.DetectionMetadata;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Experimental, observe-only connection probe: collects signals that may identify proxies and VPNs, so that the data
 * can show later which of them are worth building. It never blocks a login and never changes a verdict.
 *
 * <ul>
 *   <li><b>Ping gap:</b> the kernel measures the round trip only to the TCP peer. A SOCKS, residential or
 *   booster proxy ends that TCP connection itself, while the game's keep-alive ping still travels on to the
 *   player. A ping far above the kernel round trip therefore means a TCP-terminating proxy.</li>
 *   <li><b>Segment size and TCP stack:</b> a tunnel lowers the MTU, and the player's SYN announces the smaller MSS
 *   (WireGuard's default 1420-byte MTU gives 1380). Known access technologies are named, because DS-Lite
 *   and PPPoE also lower it. Timestamps, SACK and window scaling describe the peer's TCP stack.</li>
 *   <li><b>Reverse DNS</b> of the address, as a class only ({@link PtrClass}).</li>
 *   <li><b>Client:</b> protocol, brand, locale, how the server address was typed, Bedrock device, a hash of the
 *   skin, and the time from handshake to login and from login to join. Offline analysis compares them with the
 *   network (locale vs. country, a phone on a data-centre address, one skin on many accounts).</li>
 *   <li><b>Label:</b> Connection Guard's own verdict and sources for the address, so every signal can be compared with it.
 *   Logins that never join (denied, failed, left early) are written too, because they carry most of the positives.</li>
 * </ul>
 *
 * Without a {@link ProbeJournal} no address is kept: a probe is keyed by account and ends with the session. With one,
 * each login becomes a record with keyed hashes instead of accounts, networks and skins: a local JSON line when the
 * journal writes lines, and counts in {@link ProbeCounts} while the server contributes to Connection Guard Intel.
 * Switched on explicitly, the probe always runs; otherwise only while {@code counting} says so, and does nothing else.
 */
public final class ConnectionProbes implements DecisionObserver {
    public enum Mss { STANDARD, PPPOE, DS_LITE, WIREGUARD, TUNNEL, UNKNOWN }
    public static final int MAX_PINGS = 12;
    static final long PENDING_MILLIS = 60_000, VERDICT_MILLIS = 300_000;
    private static final int MAX_PENDING = 10_000;

    /** What the platform knows about the client once it has joined; any field may be null. */
    public static final class ClientInfo {
        final String brand, locale, bedrockOs, skinUrl;
        public ClientInfo(String brand, String locale, String bedrockOs, String skinUrl) {
            this.brand = brand; this.locale = locale; this.bedrockOs = bedrockOs; this.skinUrl = skinUrl;
        }
    }

    private final Map<String, Long> handshakes = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Probe> probes = new ConcurrentHashMap<>();
    private final Map<InetAddress, Verdict> verdicts = new ConcurrentHashMap<>();
    private final Map<Mss, AtomicLong> mssCounts = new EnumMap<>(Mss.class);
    private final AtomicLong measured = new AtomicLong(), suspected = new AtomicLong(), unavailable = new AtomicLong(), behindProxy = new AtomicLong(),
            neverJoined = new AtomicLong();
    private final long thresholdMillis;
    private final Consumer<String> log;
    private final ProbeJournal journal;
    private final ExecutorService reverseDns;
    private final boolean explicit;
    private final ProbeCounts counts;
    private final BooleanSupplier counting;

    public ConnectionProbes(long thresholdMillis, Consumer<String> log) { this(thresholdMillis, log, null, false); }
    /** @param journal where each login is written, or null; @param reverseDns look up the PTR name of each joining address */
    public ConnectionProbes(long thresholdMillis, Consumer<String> log, ProbeJournal journal, boolean reverseDns) {
        this(thresholdMillis, log, journal, reverseDns, true, null, () -> false);
    }
    /**
     * @param explicit switched on in the config: always probe; otherwise probe only while {@code counting} is true
     * @param counts the window counted for Connection Guard Intel, or null
     */
    public ConnectionProbes(long thresholdMillis, Consumer<String> log, ProbeJournal journal, boolean reverseDns, boolean explicit,
                            ProbeCounts counts, BooleanSupplier counting) {
        this.thresholdMillis = thresholdMillis; this.log = log; this.journal = journal;
        this.explicit = explicit; this.counts = counts; this.counting = counting;
        this.reverseDns = reverseDns ? new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), work -> {
            Thread thread = new Thread(work, "ConnectionGuard-probe-rdns"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.DiscardPolicy()) : null;
        for (Mss mss : Mss.values()) mssCounts.put(mss, new AtomicLong());
    }

    /** Whether logins are probed now: switched on, or counted for Intel (a linked server that contributes). */
    public boolean active() { return explicit || counts != null && counting.getAsBoolean(); }
    private boolean countingNow() { return counts != null && counting.getAsBoolean(); }

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

    /** Connection Guard's decision for an address is the label every signal is compared with. */
    @Override public void onDecision(DecisionObservation observation) {
        if (!active()) return;
        InetAddress address = literal(observation.getIp());
        if (address == null) return;
        long now = System.currentTimeMillis();
        if (verdicts.size() >= MAX_PENDING) verdicts.values().removeIf(verdict -> now - verdict.at > VERDICT_MILLIS);
        if (verdicts.size() < MAX_PENDING || verdicts.containsKey(address)) verdicts.put(address, new Verdict(observation, now));
    }

    /** The handshake opens the login; the time until the login request is part of the record. */
    public void handshake(String socketKey) {
        if (journal == null || !active()) return;
        long now = System.currentTimeMillis();
        if (handshakes.size() >= MAX_PENDING) handshakes.values().removeIf(at -> now - at > PENDING_MILLIS);
        if (handshakes.size() < MAX_PENDING) handshakes.put(socketKey, now);
    }

    /** Before login: the handshake already carries the segment size and a first round trip. */
    public Optional<TcpSample> preLogin(String socketKey, Object channel) { return preLogin(socketKey, null, channel, null, null); }
    public Optional<TcpSample> preLogin(String socketKey, InetAddress address, Object channel, Integer protocol, String virtualHost) {
        if (!active()) return Optional.empty();
        expire();
        long now = System.currentTimeMillis();
        Long handshake = handshakes.remove(socketKey);
        Optional<TcpSample> sample = TcpSample.read(channel);
        if ((sample.isPresent() || journal != null && address != null) && pending.size() < MAX_PENDING)
            pending.put(socketKey, new Pending(sample.orElse(null), address, now, handshake == null ? null : now - handshake, protocol,
                    hostKind(virtualHost)));
        return sample;
    }

    /** behindTcpProxy: the socket peer is not the player (HAProxy/TCPShield in front, or a backend behind a proxy). */
    public void joined(UUID uuid, String name, String socketKey, boolean ipv6, boolean behindTcpProxy, Object channel) {
        joined(uuid, name, socketKey, null, ipv6, behindTcpProxy, channel);
    }
    public void joined(UUID uuid, String name, String socketKey, InetAddress address, boolean ipv6, boolean behindTcpProxy, Object channel) {
        if (!active()) return;
        Pending before = pending.remove(socketKey);
        Optional<TcpSample> sample = behindTcpProxy ? Optional.empty() : TcpSample.read(channel);
        if (behindTcpProxy) behindProxy.incrementAndGet();
        else if (!sample.isPresent()) unavailable.incrementAndGet();
        boolean journaled = journal != null && address != null;
        if (!sample.isPresent() && !journaled) return;
        TcpSample login = !sample.isPresent() ? null : before != null && before.sample != null ? before.sample : sample.get();
        Probe probe = new Probe(name, address, ipv6, behindTcpProxy, login, sample.orElse(null), before,
                before == null ? null : System.currentTimeMillis() - before.at);
        probes.put(uuid, probe);
        if (journaled) lookUp(address, ptr -> probe.ptr = ptr);
    }

    /** Called on a timer; the final call logs one line, counts the result and writes the journal line. */
    public void sample(UUID uuid, long pingMillis, Object channel, boolean last) { sample(uuid, pingMillis, channel, last, null); }
    public void sample(UUID uuid, long pingMillis, Object channel, boolean last, ClientInfo client) {
        Probe probe = probes.get(uuid);
        if (probe == null) return;
        synchronized (probe) {
            if (pingMillis >= 0 && probe.pings.size() < MAX_PINGS) probe.pings.add(pingMillis);
            if (probe.latest != null) TcpSample.read(channel).ifPresent(sample -> probe.latest = sample);
            if (client != null) probe.client = client;
            if (!last || probe.done) return;
            probe.done = true;
        }
        finish(uuid, probe, true);
    }

    /** A player who leaves before the last sample still gets a journal line, marked incomplete (bots often leave fast). */
    public boolean probing(UUID uuid) { return probes.containsKey(uuid); }
    public void left(UUID uuid) { left(uuid, null); }
    public void left(UUID uuid, ClientInfo client) {
        Probe probe = probes.remove(uuid);
        if (probe == null) return;
        synchronized (probe) {
            if (probe.done) return;
            probe.done = true;
            if (client != null && probe.client == null) probe.client = client;
        }
        finish(uuid, probe, false);
    }

    /** Logins that never joined within a minute (denied, failed authentication, left during login) get their own line. */
    public void expire() { expire(System.currentTimeMillis()); }
    void expire(long now) {
        handshakes.values().removeIf(at -> now - at > PENDING_MILLIS);
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (now - entry.getValue().at <= PENDING_MILLIS || !pending.remove(entry.getKey(), entry.getValue())) continue;
            Pending gone = entry.getValue();
            if (journal == null || gone.address == null || !journal.ready()) continue;
            neverJoined.incrementAndGet();
            keep(record(null, gone.address, gone, null, gone.sample, gone.sample, Collections.emptyList(), null, null, null, false));
        }
    }

    private void finish(UUID uuid, Probe probe, boolean complete) {
        Assessment result = probe.latest == null ? null : probe.assessment(thresholdMillis);
        if (result != null && complete) {
            measured.incrementAndGet(); mssCounts.get(result.mss).incrementAndGet();
            if (result.proxySuspected) suspected.incrementAndGet();
            log.accept("Connection probe " + probe.name + ": " + result.describe());
        }
        if (journal == null || probe.address == null || !journal.ready()) return;
        List<Long> pings;
        synchronized (probe) { pings = new ArrayList<>(probe.pings); }
        JsonObject line = record(uuid, probe.address, probe.before, probe.joinMillis, probe.login, probe.latest, pings, result, probe.ptr,
                probe.client, complete);
        // Behind a TCP proxy (HAProxy, TCPShield) no TCP field was read, because it would describe the proxy.
        line.addProperty("behind_tcp_proxy", probe.behindProxy);
        keep(line);
    }

    /** The local line (when the journal writes lines) and the counts for Intel (while contributing). */
    private void keep(JsonObject record) {
        journal.write(record);
        if (countingNow()) counts.add(record);
    }

    JsonObject record(UUID uuid, InetAddress address, Pending login, Long joinMillis, TcpSample handshake, TcpSample latest, List<Long> pings,
                      Assessment result, PtrClass ptr, ClientInfo client, boolean complete) {
        boolean ipv6 = address.getAddress().length == 16;
        JsonObject out = new JsonObject();
        out.addProperty("v", 1);
        out.addProperty("t", System.currentTimeMillis() / 60_000 * 60); // seconds, to the minute
        out.addProperty("days_running", journal.daysRunning());
        out.addProperty("joined", uuid != null);
        out.addProperty("complete", complete);
        out.addProperty("family", ipv6 ? 6 : 4);
        out.addProperty("net", journal.hash("net", prefix(address)));
        if (uuid != null) {
            String account = journal.hash("acct", uuid.toString());
            out.addProperty("acct", account);
            out.addProperty("new_acct", journal.firstSeen(account));
        }
        if (handshake != null) {
            JsonObject tcp = new JsonObject();
            tcp.addProperty("peer_mss", handshake.peerMss());
            tcp.addProperty("mss_class", classify(handshake.peerMss(), ipv6).name().toLowerCase(Locale.ROOT));
            tcp.addProperty("timestamps", handshake.timestamps);
            tcp.addProperty("sack", handshake.sack);
            tcp.addProperty("peer_wscale", handshake.peerWscale);
            tcp.addProperty("login_rtt_ms", round(handshake.rttMillis()));
            if (latest != null) { tcp.addProperty("rtt_ms", round(latest.rttMillis())); tcp.addProperty("rttvar_ms", round(latest.rttVarMicros / 1000.0)); }
            out.add("tcp", tcp);
        }
        JsonObject ping = new JsonObject();
        List<Long> sorted = new ArrayList<>(pings);
        sorted.removeIf(value -> value < 0);
        Collections.sort(sorted);
        ping.addProperty("n", sorted.size());
        if (!sorted.isEmpty()) { ping.addProperty("min_ms", sorted.get(0)); ping.addProperty("median_ms", sorted.get(sorted.size() / 2)); }
        if (result != null && result.pings > 0) { ping.addProperty("gap_ms", result.gapMillis); ping.addProperty("proxy_suspected", result.proxySuspected); }
        out.add("ping", ping);
        if (ptr != null) out.addProperty("ptr", ptr.name().toLowerCase(Locale.ROOT));
        JsonObject c = new JsonObject();
        if (login != null) {
            c.addProperty("protocol", login.protocol);
            c.addProperty("vhost", login.vhost);
            c.addProperty("handshake_to_login_ms", login.handshakeMillis);
        }
        c.addProperty("login_to_join_ms", joinMillis);
        if (client != null) {
            c.addProperty("brand", clip(client.brand, 32));
            c.addProperty("locale", clip(client.locale, 16));
            c.addProperty("bedrock_os", clip(client.bedrockOs, 24));
            if (client.skinUrl != null) c.addProperty("skin", journal.hash("skin", client.skinUrl));
        }
        out.add("client", c);
        Verdict verdict = verdicts.get(address);
        if (verdict != null) out.add("verdict", verdict.json());
        return out;
    }

    private void lookUp(InetAddress address, Consumer<PtrClass> result) {
        if (reverseDns == null) return;
        try {
            reverseDns.execute(() -> {
                // Blocks for as long as the resolver takes, so it runs on its own small pool with a bounded queue.
                InetAddress fresh = literal(address.getHostAddress());
                if (fresh != null) result.accept(PtrClass.of(fresh.getCanonicalHostName(), fresh.getHostAddress()));
            });
        } catch (RejectedExecutionException stopped) { /* closed */ }
    }

    private static Double round(double value) { return Math.round(value * 10) / 10.0; }
    static String clip(String value, int max) {
        if (value == null) return null;
        String clean = value.replaceAll("[^\\x20-\\x7e]", "?");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
    /** How the player typed the server address: a name, a bare address, or nothing. The name itself is not kept. */
    static String hostKind(String host) {
        if (host == null || host.isEmpty()) return null;
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (h.matches("[0-9.]+") || h.contains(":")) return "ip";
        return "name";
    }
    /** The /24 (IPv4) or /48 (IPv6) the address belongs to. */
    static String prefix(InetAddress address) {
        byte[] b = address.getAddress();
        StringBuilder out = new StringBuilder();
        if (b.length == 4) return out.append(b[0] & 255).append('.').append(b[1] & 255).append('.').append(b[2] & 255).append(".0/24").toString();
        for (int i = 0; i < 6; i += 2) out.append(Integer.toHexString((b[i] & 255) << 8 | b[i + 1] & 255)).append(':');
        return out.append(":/48").toString();
    }
    /** Parses an address literal without ever asking DNS. */
    static InetAddress literal(String ip) {
        if (ip == null || ip.isEmpty() || !ip.matches("[0-9A-Fa-f:.]+") || !ip.matches(".*[.:].*")) return null;
        try { return InetAddress.getByName(ip); } catch (UnknownHostException | SecurityException invalid) { return null; }
    }

    public Optional<Assessment> assessment(UUID uuid) {
        Probe probe = probes.get(uuid);
        return probe == null || probe.latest == null ? Optional.empty() : Optional.of(probe.assessment(thresholdMillis));
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        StringBuilder mss = new StringBuilder();
        mssCounts.forEach((kind, count) -> { if (count.get() > 0) mss.append(' ').append(kind.name().toLowerCase(Locale.ROOT)).append('=').append(count.get()); });
        lines.add("Connection probe (experimental, observe only): measured=" + measured.get() + " proxy-suspected=" + suspected.get()
                + " no-tcp-info=" + unavailable.get() + " behind-tcp-proxy=" + behindProxy.get() + " threshold=" + thresholdMillis + "ms");
        if (mss.length() > 0) lines.add("Segment sizes:" + mss);
        if (journal != null && journal.writesLines()) lines.add("Journal: " + journal.directory() + " lines=" + journal.written() + " dropped=" + journal.dropped()
                + " never-joined=" + neverJoined.get() + " (local only, kept " + ProbeJournal.RETENTION_DAYS + " days)");
        if (counts != null) lines.add("Counts for Connection Guard Intel: " + (countingNow()
                ? "on (signal values per verdict, sent every six hours; never a record, address or account)"
                : "off (only linked servers that contribute network signals)"));
        probes.values().stream().filter(probe -> probe.latest != null).sorted(Comparator.comparing(probe -> probe.name)).limit(20)
                .forEach(probe -> lines.add(probe.name + ": " + probe.assessment(thresholdMillis).describe()));
        return lines;
    }

    public void close() {
        if (reverseDns != null) reverseDns.shutdownNow();
        if (journal != null) journal.close();
    }

    static final class Pending {
        final TcpSample sample; final InetAddress address; final long at; final Long handshakeMillis; final Integer protocol; final String vhost;
        Pending(TcpSample sample, InetAddress address, long at, Long handshakeMillis, Integer protocol, String vhost) {
            this.sample = sample; this.address = address; this.at = at; this.handshakeMillis = handshakeMillis; this.protocol = protocol; this.vhost = vhost;
        }
    }
    private static final class Verdict {
        final DecisionObservation observation; final long at;
        Verdict(DecisionObservation observation, long at) { this.observation = observation; this.at = at; }
        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("phase", observation.getPhase().name());
            out.addProperty("outcome", observation.getOutcome().name());
            out.addProperty("reason", observation.getReason().name());
            out.addProperty("vpn", observation.getVpnCheck().name());
            out.addProperty("geo", observation.getGeoCheck().name());
            JsonArray flags = new JsonArray();
            observation.getFlags().forEach(flag -> flags.add(flag.name()));
            out.add("flags", flags);
            JsonArray sources = new JsonArray();
            Long asn = null; String country = null;
            for (DecisionObservation.Source source : observation.getSources()) {
                JsonObject s = new JsonObject();
                s.addProperty("id", source.getId());
                s.addProperty("status", source.getObservation().getStatus().name());
                DetectionMetadata meta = source.getObservation().getMetadata();
                if (meta != null) {
                    JsonArray types = new JsonArray();
                    if (meta.getTypes() != null) meta.getTypes().forEach((type, on) -> { if (Boolean.TRUE.equals(on)) types.add(type.name()); });
                    if (types.size() > 0) s.add("types", types);
                    if (asn == null) asn = meta.getAsn();
                    if (country == null) country = meta.getCountry();
                }
                sources.add(s);
            }
            out.add("sources", sources);
            out.addProperty("asn", asn);
            out.addProperty("country", country);
            return out;
        }
    }
    private static final class Probe {
        final String name; final InetAddress address; final boolean ipv6, behindProxy; final TcpSample login; final Pending before; final Long joinMillis;
        final List<Long> pings = new ArrayList<>();
        TcpSample latest; boolean done; volatile PtrClass ptr; volatile ClientInfo client;
        Probe(String name, InetAddress address, boolean ipv6, boolean behindProxy, TcpSample login, TcpSample latest, Pending before, Long joinMillis) {
            this.name = name; this.address = address; this.ipv6 = ipv6; this.behindProxy = behindProxy; this.login = login; this.latest = latest;
            this.before = before; this.joinMillis = joinMillis;
        }
        synchronized Assessment assessment(long threshold) {
            Assessment now = assess(latest, pings, ipv6, threshold);
            // The segment size comes from the handshake; later samples may reflect path MTU discovery instead.
            return new Assessment(classify(login.peerMss(), ipv6), login.peerMss(), now.tcpRttMillis, now.minPingMillis, now.gapMillis,
                    now.pings, now.proxySuspected);
        }
    }
}
