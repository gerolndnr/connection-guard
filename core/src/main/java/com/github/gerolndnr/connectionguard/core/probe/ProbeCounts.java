package com.github.gerolndnr.connectionguard.core.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * The connection probe's contribution to Connection Guard Intel: per sync window, how often each signal value occurred
 * on logins Connection Guard judged VPN, hosting, clean or could not judge. Only these counts leave the server, never a
 * record, hash, address, account or name. Records stay in memory for the window and are forgotten when it is drained;
 * per-network signals (accounts per prefix, spread of their pings, a skin on several accounts) are computed from the
 * window's records first. A window with fewer than {@link #MIN_LOGINS} logins is held back and merged into the next one.
 * {@code ci/fixtures/connection-probe/analyze.py} computes the same signals from a local journal.
 */
public final class ProbeCounts {
    public static final int MIN_LOGINS = 5, MAX_RECORDS = 4096, MAX_KEYS = 600;
    static final String[] LABELS = { "vpn", "hosting", "clean", "unlabelled" };
    private static final Set<String> VPN_TYPES = new HashSet<>(Arrays.asList("VPN", "PROXY", "TOR", "RELAY"));
    private static final Set<String> MOBILE = new HashSet<>(Arrays.asList("google", "ios", "amazon", "windows_phone"));
    private static final String[] BRANDS = { "vanilla", "fabric", "neoforge", "forge", "quilt", "lunarclient", "badlion", "feather", "geyser", "labymod" };

    private final List<JsonObject> records = new ArrayList<>();
    private final Map<String, Long> carried = new TreeMap<>();
    private long carriedLogins, overflow;

    public synchronized void add(JsonObject record) {
        if (records.size() >= MAX_RECORDS) { overflow++; return; }
        records.add(record);
    }

    /** Forgets the window (contribution turned off or the server unlinked). */
    public synchronized void clear() { records.clear(); carried.clear(); carriedLogins = 0; overflow = 0; }

    /** The window as sent, or null while it holds fewer than {@link #MIN_LOGINS} logins: {"logins","overflow","counts":[{"s","v","l","n"}]}. */
    public synchronized JsonObject drain() {
        long logins = carriedLogins + records.size() + overflow;
        if (logins < MIN_LOGINS) return null;
        Map<String, Long> counts = new TreeMap<>(carried);
        Map<String, Network> networks = networks(records);
        Map<String, Integer> skins = skins(records);
        for (JsonObject record : records) {
            String label = label(record);
            add(counts, "all", "all", label);
            features(record, networks, skins).forEach((signal, value) -> add(counts, signal, value, label));
        }
        JsonArray list = new JsonArray();
        long dropped = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (list.size() >= MAX_KEYS) { dropped++; continue; }
            String[] key = entry.getKey().split("\\|", 3);
            JsonObject j = new JsonObject();
            j.addProperty("s", key[0]); j.addProperty("v", key[1]); j.addProperty("l", key[2]);
            j.addProperty("n", Math.min(1_000_000_000L, entry.getValue()));
            list.add(j);
        }
        JsonObject out = new JsonObject();
        out.addProperty("logins", Math.min(1_000_000_000L, logins));
        out.addProperty("overflow", Math.min(1_000_000_000L, overflow + dropped));
        out.add("counts", list);
        records.clear(); carried.clear(); carriedLogins = 0; overflow = 0;
        return out;
    }

    /** A failed sync puts its window back; it is merged into the next one as counts. */
    public synchronized void restore(JsonObject window) {
        carriedLogins += window.get("logins").getAsLong() - window.get("overflow").getAsLong();
        overflow += window.get("overflow").getAsLong();
        for (JsonElement e : window.getAsJsonArray("counts")) {
            JsonObject j = e.getAsJsonObject();
            carried.merge(j.get("s").getAsString() + "|" + j.get("v").getAsString() + "|" + j.get("l").getAsString(), j.get("n").getAsLong(), Long::sum);
        }
    }

    synchronized int size() { return records.size(); }

    private static void add(Map<String, Long> counts, String signal, String value, String label) {
        counts.merge(signal + "|" + value + "|" + label, 1L, Long::sum);
    }

    /** The label is Connection Guard's own verdict for the address. */
    static String label(JsonObject record) {
        JsonObject verdict = object(record, "verdict");
        if (verdict == null) return "unlabelled";
        Set<String> types = new HashSet<>();
        boolean positive = false;
        JsonArray sources = verdict.has("sources") ? verdict.getAsJsonArray("sources") : new JsonArray();
        for (JsonElement e : sources) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("types")) for (JsonElement t : s.getAsJsonArray("types")) types.add(t.getAsString());
            positive |= "POSITIVE".equals(text(s, "status"));
        }
        if ("POSITIVE".equals(text(verdict, "vpn")) || "VPN_FLAG".equals(text(verdict, "reason"))) return "vpn";
        if (positive && !Collections.disjoint(types, VPN_TYPES)) return "vpn";
        if (types.contains("HOSTING")) return "hosting";
        String vpn = text(verdict, "vpn");
        return "NEGATIVE".equals(vpn) || "KNOWN".equals(vpn) ? "clean" : "unlabelled";
    }

    /** Every signal of one login as signal → value; values come from closed sets or buckets, never free text. */
    static Map<String, String> features(JsonObject r, Map<String, Network> networks, Map<String, Integer> skins) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonObject tcp = object(r, "tcp"), ping = object(r, "ping"), client = object(r, "client"), verdict = object(r, "verdict");
        boolean joined = bool(r, "joined");
        put(out, "joined", joined);
        if (joined) put(out, "left_early", !bool(r, "complete"));
        put(out, "behind_tcp_proxy", r.has("behind_tcp_proxy") ? bool(r, "behind_tcp_proxy") : null);
        put(out, "family", number(r, "family"));
        if (tcp != null) {
            put(out, "mss_class", text(tcp, "mss_class"));
            put(out, "tcp_timestamps", tcp.has("timestamps") ? bool(tcp, "timestamps") : null);
            put(out, "tcp_sack", tcp.has("sack") ? bool(tcp, "sack") : null);
            put(out, "peer_wscale", number(tcp, "peer_wscale"));
            put(out, "tcp_rttvar", bucket(decimal(tcp, "rttvar_ms"), 2, 5, 10, 25));
        }
        if (ping != null) {
            put(out, "ping_gap", bucket(decimal(ping, "gap_ms"), 10, 20, 40, 80, 150));
            put(out, "proxy_suspected", ping.has("proxy_suspected") ? bool(ping, "proxy_suspected") : null);
        }
        put(out, "ptr", text(r, "ptr"));
        if (client != null) {
            put(out, "vhost", text(client, "vhost"));
            put(out, "brand", brand(text(client, "brand")));
            put(out, "bedrock_os", text(client, "bedrock_os"));
            put(out, "protocol", number(client, "protocol"));
            put(out, "handshake_to_login", bucket(decimal(client, "handshake_to_login_ms"), 50, 200, 1000, 5000));
            put(out, "login_to_join", bucket(decimal(client, "login_to_join_ms"), 200, 500, 1000, 3000));
            String locale = text(client, "locale"), country = verdict == null ? null : text(verdict, "country");
            if (locale != null && country != null && locale.contains("_"))
                put(out, "locale_country_mismatch", !locale.substring(locale.indexOf('_') + 1).equalsIgnoreCase(country));
            String os = text(client, "bedrock_os");
            if (os != null && MOBILE.contains(os.toLowerCase(Locale.ROOT)) && "hosting".equals(label(r))) put(out, "mobile_on_hosting", true);
            String skin = text(client, "skin");
            if (skin != null && skins.getOrDefault(skin, 0) >= 3) put(out, "skin_shared_by_3plus_accounts", true);
        }
        // New accounts only mean something once the probe has run for a week.
        if (r.has("new_acct") && number(r, "days_running") != null && number(r, "days_running") >= 7) put(out, "new_account", bool(r, "new_acct"));
        Network n = networks.get(text(r, "net"));
        if (n != null) {
            put(out, "accounts_per_net", bucket((double) n.accounts.size(), 2, 5, 20, 100));
            if (n.accounts.size() >= 5) put(out, "new_account_share_per_net", bucket((double) n.fresh.size() / n.accounts.size(), 0.2, 0.5, 0.8));
            Double spread = n.pingSpread();
            if (spread != null) put(out, "ping_spread_per_net", bucket(spread, 10, 30, 80));
        }
        return out;
    }

    static final class Network {
        final Set<String> accounts = new HashSet<>(), fresh = new HashSet<>();
        final List<Double> pings = new ArrayList<>();
        /** One exit, players from all over the world: their pings spread far wider than behind one home connection. */
        Double pingSpread() {
            if (pings.size() < 3 || accounts.size() < 3) return null;
            double mean = pings.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return Math.sqrt(pings.stream().mapToDouble(p -> (p - mean) * (p - mean)).sum() / pings.size());
        }
    }
    static Map<String, Network> networks(List<JsonObject> records) {
        Map<String, Network> out = new HashMap<>();
        for (JsonObject r : records) {
            String account = text(r, "acct"), net = text(r, "net");
            if (account == null || net == null) continue;
            Network n = out.computeIfAbsent(net, key -> new Network());
            n.accounts.add(account);
            if (bool(r, "new_acct")) n.fresh.add(account);
            JsonObject ping = object(r, "ping");
            Double min = ping == null ? null : decimal(ping, "min_ms");
            if (min != null) n.pings.add(min);
        }
        return out;
    }
    static Map<String, Integer> skins(List<JsonObject> records) {
        Map<String, Set<String>> accounts = new HashMap<>();
        for (JsonObject r : records) {
            JsonObject client = object(r, "client");
            String skin = client == null ? null : text(client, "skin"), account = text(r, "acct");
            if (skin != null && account != null) accounts.computeIfAbsent(skin, key -> new HashSet<>()).add(account);
        }
        Map<String, Integer> out = new HashMap<>();
        accounts.forEach((skin, set) -> out.put(skin, set.size()));
        return out;
    }

    static String brand(String value) {
        if (value == null || value.isEmpty()) return null;
        String v = value.toLowerCase(Locale.ROOT);
        for (String known : BRANDS) if (v.contains(known)) return known;
        return "other";
    }
    static String bucket(Double value, double... edges) {
        if (value == null) return null;
        for (double edge : edges) if (value < edge) return "<" + format(edge);
        return ">=" + format(edges[edges.length - 1]);
    }
    private static String format(double edge) { return edge == Math.rint(edge) ? Long.toString((long) edge) : Double.toString(edge); }
    /** Values are lower case, at most 24 characters of [a-z0-9_<>=.-]; anything else would be free text. */
    private static void put(Map<String, String> out, String signal, Object value) {
        if (value == null) return;
        String v = value.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_<>=.\\-]", "_");
        if (!v.isEmpty()) out.put(signal, v.length() > 24 ? v.substring(0, 24) : v);
    }
    private static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null; }
    private static String text(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null; }
    private static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean(); }
    private static Long number(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsLong() : null; }
    private static Double decimal(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsDouble() : null; }
}
