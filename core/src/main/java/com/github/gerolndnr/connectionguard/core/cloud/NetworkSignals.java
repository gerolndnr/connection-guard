package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.AdmissionObservation;
import com.github.gerolndnr.connectionguard.api.v1.AdmissionResponse;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.core.lookup.LocalAddresses;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.util.*;

/**
 * Network signals for Connection Guard Intel: per sync window and per network prefix (IPv4 /24, IPv6 /48), how many
 * logins and distinct accounts this server saw, and how its checks and its operator judged them. Many accounts from
 * one prefix across many servers point to a shared exit (a proxy); bans and operator allowances label it.
 * Only the prefix and counts leave the server: never an address, account, name or rule. Accounts are counted in
 * memory for the current window and forgotten when it is drained. Off with {@code cloud.contribute-signals: false}.
 * A window spans six hours, so the cloud stores each prefix a few times a day instead of once per sync.
 */
final class NetworkSignals {
    static final int MAX_PREFIXES = 512;
    static long windowMillis = java.util.concurrent.TimeUnit.HOURS.toMillis(6); // tests shorten it
    private static final int MAX_ACCOUNTS = 64;

    static final class Counts {
        long logins, vpn, denied, banned, allowedByRule;
        final Set<UUID> accounts = new HashSet<>();
        long extraAccounts; // restored windows carry an account count, not the accounts
        long accounts() { return Math.min(1_000_000L, accounts.size() + extraAccounts); }
    }

    private final Map<String, Counts> prefixes = new TreeMap<>();
    private long overflow, windowStart = System.currentTimeMillis();

    boolean due(long now) { return now - windowStart >= windowMillis; }

    /** Called under the recorder's lock. */
    void record(DecisionObservation o) {
        String prefix = prefix(o.getIp());
        if (prefix == null) return;
        Counts c = prefixes.get(prefix);
        if (c == null) {
            if (prefixes.size() >= MAX_PREFIXES) { overflow++; return; }
            prefixes.put(prefix, c = new Counts());
        }
        c.logins++;
        UUID uuid = o.getTrustedUuid().orElse(null);
        if (uuid != null && c.accounts.size() < MAX_ACCOUNTS) c.accounts.add(uuid);
        if (o.getVpnCheck() == DecisionObservation.Check.POSITIVE) c.vpn++;
        if (o.getOutcome() == DecisionObservation.Outcome.DENY) c.denied++;
        for (AdmissionObservation a : o.getAdmissionChecks())
            if (a.getResponse().getReason() == AdmissionResponse.Reason.ACTIVE_BAN) { c.banned++; break; }
        for (DecisionObservation.Rule r : o.getRules())
            if (r.isSelected() && r.getEffect() != DecisionObservation.Effect.DENY && r.getEvaluatedScope() != DecisionObservation.Scope.GEO) {
                c.allowedByRule++; break;
            }
    }

    /** The window as sent: {"prefixes":[{"p","logins","accounts","vpn","denied","banned","allowed_by_rule"}],"overflow"}. */
    JsonObject drain() {
        JsonArray list = new JsonArray();
        prefixes.forEach((p, c) -> {
            JsonObject j = new JsonObject();
            j.addProperty("p", p);
            j.addProperty("logins", Math.min(1_000_000_000L, c.logins));
            j.addProperty("accounts", c.accounts());
            j.addProperty("vpn", Math.min(1_000_000_000L, c.vpn));
            j.addProperty("denied", Math.min(1_000_000_000L, c.denied));
            j.addProperty("banned", Math.min(1_000_000_000L, c.banned));
            j.addProperty("allowed_by_rule", Math.min(1_000_000_000L, c.allowedByRule));
            list.add(j);
        });
        JsonObject out = new JsonObject();
        out.add("prefixes", list);
        out.addProperty("overflow", Math.min(1_000_000_000L, overflow));
        prefixes.clear(); overflow = 0; windowStart = System.currentTimeMillis();
        return out;
    }

    /** A failed sync puts its window back; account counts add up, so a restored prefix may count an account twice. */
    void restore(JsonObject window) {
        overflow += window.get("overflow").getAsLong();
        for (com.google.gson.JsonElement e : window.getAsJsonArray("prefixes")) {
            JsonObject j = e.getAsJsonObject();
            String p = j.get("p").getAsString();
            Counts c = prefixes.get(p);
            if (c == null) {
                if (prefixes.size() >= MAX_PREFIXES) { overflow++; continue; }
                prefixes.put(p, c = new Counts());
            }
            c.logins += j.get("logins").getAsLong(); c.extraAccounts += j.get("accounts").getAsLong();
            c.vpn += j.get("vpn").getAsLong(); c.denied += j.get("denied").getAsLong();
            c.banned += j.get("banned").getAsLong(); c.allowedByRule += j.get("allowed_by_rule").getAsLong();
        }
    }

    /** The /24 or /48 of a public literal address; null for anything else. */
    static String prefix(String literal) {
        if (literal == null || !literal.matches("[0-9a-fA-F:.]+") || LocalAddresses.classify(literal).isPresent()) return null;
        try {
            byte[] b = InetAddress.getByName(literal).getAddress();
            if (b.length == 4) return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".0/24";
            return String.format(Locale.ROOT, "%x:%x:%x::/48", (b[0] & 0xFF) << 8 | b[1] & 0xFF, (b[2] & 0xFF) << 8 | b[3] & 0xFF,
                    (b[4] & 0xFF) << 8 | b[5] & 0xFF);
        } catch (Exception invalid) { return null; }
    }
}
