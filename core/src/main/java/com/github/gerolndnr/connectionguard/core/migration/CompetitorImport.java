package com.github.gerolndnr.connectionguard.core.migration;

import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;

/** Schema-specific mapping, not a copy of another product's scoring/detection engine. */
final class CompetitorImport {
    final String source;
    final Path directory;
    final Map<String, Object> input, defaults, target;
    final Map<String, String> fingerprints = new TreeMap<>();
    final List<AccessRule> rules = new ArrayList<>();
    final List<String> changes = new ArrayList<>(), warnings = new ArrayList<>(), blockers = new ArrayList<>();
    private final Set<String> ruleKeys = new HashSet<>();
    private final List<String> keyed = new ArrayList<>();
    private final boolean exportedRules;
    CompetitorImport(String source, Path directory, Map<String, Object> defaults, Map<String, Object> target) throws IOException {
        this.source = source; this.directory = directory; this.defaults = defaults; this.target = target;
        input = document("config.yml");
        List<String> schemaKeys;
        switch (source) {
            case "foxgate": schemaKeys = Arrays.asList("key", "configuration", "antivpn"); break;
            case "proxyshield": schemaKeys = Arrays.asList("settings", "detection", "api", "whitelist", "blacklist", "country"); break;
            case "vpnguard": schemaKeys = Arrays.asList("providers", "join-enforcement", "config-version"); break;
            case "kaurivpn": schemaKeys = Arrays.asList("license", "kickPlayers", "database", "prefixWhitelists", "countries"); break;
            case "advancedantivpn": schemaKeys = Arrays.asList("Services", "Actions", "Database", "GeoLite2"); break;
            default: throw new IOException("Unsupported competitor.");
        }
        if (schemaKeys.stream().noneMatch(input::containsKey)) throw new IOException("Unrecognized competitor config schema; no default assumptions applied.");
        if (source.equals("vpnguard") && input.containsKey("config-version") && !Objects.equals(input.get("config-version"), 2))
            throw new IOException("Unsupported VPNGuard config version; preview rejected.");
        track("migration-rules.yml"); exportedRules = Files.exists(directory.resolve("migration-rules.yml"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (exportedRules) {
            Map<String, Object> export = document("migration-rules.yml");
            if (!Objects.equals(export.get("schema"), 1) || !Boolean.TRUE.equals(export.get("complete"))
                    || !new HashSet<>(Arrays.asList("schema", "complete", "allow", "deny")).equals(export.keySet()))
                throw new IOException("Admin-rule export must declare schema:1, complete:true, allow:[], deny:[] (values redacted).");
            entries(export, "allow", AccessRule.Effect.ALLOW); entries(export, "deny", AccessRule.Effect.DENY);
            warnings.add("Explicit complete local admin-rule export selected; database rules are taken from this export, never a remote database.");
        }
    }
    Map<String, Object> document(String name) throws IOException {
        Path path = directory.resolve(name); track(name); return MigrationFiles.yaml(MigrationFiles.read(path, MigrationFiles.MAX_BYTES));
    }
    void track(String name) throws IOException { fingerprints.put(name, MigrationFiles.fingerprint(directory.resolve(name))); }
    Object get(String path) { return MigrationFiles.get(input, path); }
    static boolean bool(Map<String, Object> values, String path, boolean fallback) {
        Object value = MigrationFiles.get(values, path); if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean at " + path + " (value redacted).");
        return (Boolean) value;
    }
    boolean bool(String path, boolean fallback) { return bool(input, path, fallback); }
    static String string(Map<String, Object> values, String path, String fallback) {
        Object value = MigrationFiles.get(values, path); if (value == null) return fallback;
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected text at " + path + " (value redacted).");
        return (String) value;
    }
    String string(String path, String fallback) { return string(input, path, fallback); }
    static List<?> list(Map<String, Object> values, String path) {
        Object value = MigrationFiles.get(values, path); if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected list at " + path + " (values redacted).");
        return (List<?>) value;
    }
    void map(String path, Object value) {
        Object current = MigrationFiles.get(target, path), bundled = MigrationFiles.get(defaults, path);
        if (Objects.equals(current, value)) return;
        if (!Objects.equals(current, bundled)) { warnings.add("Existing Connection Guard setting kept: " + path); return; }
        MigrationFiles.set(target, path, value); changes.add(path + (path.endsWith("api-key") ? " (secret redacted)" : ""));
    }
    void importAll() throws IOException {
        switch (source) {
            case "foxgate": foxgate(); break;
            case "proxyshield": proxyshield(); break;
            case "vpnguard": vpnguard(); break;
            case "kaurivpn": kauri(); break;
            case "advancedantivpn": advanced(); break;
            default: throw new IllegalArgumentException("Unsupported competitor.");
        }
        // Compatible paid/keyed sources improve the chain; do not transplant consensus thresholds.
        if (!keyed.isEmpty()) {
            List<String> order = new ArrayList<>(); order.add("proxycheck"); order.addAll(keyed);
            for (Object old : list(target, "provider.vpn-failover.order")) if (old instanceof String && !order.contains(old)) order.add((String) old);
            map("provider.vpn-failover.order", order);
        }
        warnings.add("Connection Guard's failover, local Tor/Intel, lookup limits and identity checks are retained. Legacy scores, provider caches and automatic trust history are not imported.");
        warnings.add("Review changed detection/hosting semantics in docs/MIGRATIONS.md; this is not a claim of equivalent or improved benchmark results.");
        warnings.add("No foreign command, webhook, reverse proxy, database credential or player-name bypass is enabled by this migration.");
    }
    void provider(Map<String, Object> doc, String section, String id, String enabled, String key) {
        if (!bool(doc, section + "." + enabled, false)) return;
        String secret = key == null ? "" : string(doc, section + "." + key, "").trim();
        boolean requiresKey = Arrays.asList("iphub", "vpnapi", "ipqualityscore").contains(id);
        if (requiresKey && (secret.isEmpty() || secret.toUpperCase(Locale.ROOT).contains("YOUR_") || secret.equalsIgnoreCase("API_KEY"))) {
            warnings.add("Source " + id + " has no usable key; keep CG's keyless failover instead."); return;
        }
        // ipcheck remains opt-in. Its source being on does not hide its missing operator/terms/privacy.
        if (id.equals("ipcheck")) warnings.add("Source enabled ip-check.net: no published operator, terms or privacy policy; explicitly reviewed apply enables this recipient.");
        map("provider.vpn." + id + ".enabled", true);
        if (!secret.isEmpty() && key != null) {
            map("provider.vpn." + id + ".api-key", secret);
            if (!id.equals("proxycheck") && !keyed.contains(id)) keyed.add(id);
        }
    }
    void countries(List<?> values, boolean whitelist) { countries(values, whitelist, false); }
    void countries(List<?> values, boolean whitelist, boolean active) {
        if (values.isEmpty() && !active) return;
        if (values.isEmpty() && whitelist) warnings.add("Source has an active empty country allowlist: every KNOWN country is denied; UNKNOWN follows geo failure policy.");
        List<String> codes = new ArrayList<>();
        for (Object raw : values) {
            if (!(raw instanceof String) || !((String) raw).matches("(?i)[a-z]{2}")) throw new IllegalArgumentException("Country rules need two-letter ISO codes (values redacted).");
            String code = ((String) raw).toUpperCase(Locale.ROOT); if (!codes.contains(code)) codes.add(code);
        }
        if (codes.size() > 250) throw new IllegalArgumentException("Too many country codes.");
        map("behavior.geo.list", codes); map("behavior.geo.type", whitelist ? "WHITELIST" : "BLACKLIST");
        map("provider.geo.service", "ProxyCheck");
        warnings.add("Country rules use CG's shared ProxyCheck lookup, not the old geolocation engine. UNKNOWN follows the displayed geo failure policy.");
    }
    void entries(Map<String, Object> values, String path, AccessRule.Effect effect) {
        for (Object entry : list(values, path)) {
            if (!(entry instanceof String)) throw new IllegalArgumentException("Invalid rule list at " + path + " (values redacted).");
            selector((String) entry, effect, AccessRule.Scope.ALL, path);
        }
    }
    void selector(String raw, AccessRule.Effect effect, AccessRule.Scope scope, String origin) {
        try {
            String text = raw.trim();
            if (text.indexOf('*') >= 0) {
                String[] octets = text.split("\\.", -1); int prefix = 0; boolean wildcard = false;
                if (octets.length != 4) throw new IllegalArgumentException();
                for (int i = 0; i < 4; i++) {
                    if (octets[i].equals("*")) { wildcard = true; octets[i] = "0"; }
                    else { if (wildcard || !octets[i].matches("[0-9]{1,3}") || Integer.parseInt(octets[i]) > 255) throw new IllegalArgumentException(); prefix += 8; }
                }
                text = String.join(".", octets) + "/" + prefix;
            }
            if (text.indexOf('-') >= 0 && !text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                range(text, effect, scope, origin); return;
            }
            add(text, effect, scope);
        } catch (IllegalArgumentException invalid) {
            String line = "Unresolved " + effect + " selector from " + origin + " (value redacted). Names/prefixes, arbitrary wildcards and unknown formats need explicit UUID/CIDR rules.";
            if (effect == AccessRule.Effect.DENY) blockers.add(line); else warnings.add(line);
        }
    }
    private void range(String text, AccessRule.Effect effect, AccessRule.Scope scope, String origin) {
        String[] endpoints = text.split("-", -1); if (endpoints.length != 2) throw new IllegalArgumentException();
        try {
            byte[] start = InetAddress.getByName(Exemptions.normalize(endpoints[0].trim())).getAddress();
            byte[] end = InetAddress.getByName(Exemptions.normalize(endpoints[1].trim())).getAddress();
            if (start.length != end.length) throw new IllegalArgumentException();
            int bits = start.length * 8; BigInteger low = new BigInteger(1, start), high = new BigInteger(1, end);
            if (low.compareTo(high) > 0) throw new IllegalArgumentException();
            while (low.compareTo(high) <= 0) {
                int power = Math.min(low.signum() == 0 ? bits : low.getLowestSetBit(), high.subtract(low).add(BigInteger.ONE).bitLength() - 1);
                byte[] bytes = new byte[start.length], encoded = low.toByteArray();
                System.arraycopy(encoded, Math.max(0, encoded.length - bytes.length), bytes, Math.max(0, bytes.length - encoded.length), Math.min(bytes.length, encoded.length));
                add(InetAddress.getByAddress(bytes).getHostAddress() + "/" + (bits - power), effect, scope);
                low = low.add(BigInteger.ONE.shiftLeft(power));
            }
        } catch (java.net.UnknownHostException invalid) { throw new IllegalArgumentException(); }
    }
    private void add(String target, AccessRule.Effect effect, AccessRule.Scope scope) {
        AccessRule rule = new AccessRule("migration-" + source + "-" + MigrationFiles.hash((effect + ":" + scope + ":" + target).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 20),
                effect, scope, target, 0, "Imported explicit admin rule from " + source);
        String key = effect + ":" + scope + ":" + rule.getTarget();
        if (ruleKeys.add(key)) rules.add(rule);
        if (rules.size() > 512) throw new IllegalArgumentException("Migration exceeds CG's 512-rule limit; no rules applied.");
    }
    private void foxgate() throws IOException {
        map("operation.mode", bool("configuration.scanner.passive_mode", false) ? "OBSERVE" : "ENFORCE");
        if (get("antivpn.actions") != null) {
            List<?> actions = list(input, "antivpn.actions");
            if (actions.isEmpty()) map("behavior.vpn.kick-player", false);
            else if (!actions.contains("kick") && !exportedRules) blockers.add("Review FoxGate custom enforcement actions with a complete local export; no foreign command is executed.");
        }
        warnings.add("FoxGate maxFlags/continuation, bogon, playtime bypass and prefix-based Geyser rules are not equivalent to CG's evidence and authenticated UUID rules.");
        Path whitelist = directory.resolve("whitelist.yml"); track("whitelist.yml");
        if (Files.exists(whitelist)) { Map<String, Object> doc = document("whitelist.yml"); entries(doc, "ips", AccessRule.Effect.ALLOW); entries(doc, "names", AccessRule.Effect.ALLOW); }
        for (String[] provider : new String[][]{{"proxycheck","proxycheck"},{"vpnapi","vpnapi"},{"ipqualityscore","ipqualityscore"},{"iphub","iphub"},{"blackbox","blackbox"},{"ip-check","ipcheck"},{"zowicentral","zowi"},{"ipquery","ipquery"},{"ip-api","ip-api"}}) {
            String filename = "services/" + provider[0] + ".yml"; track(filename);
            if (!Files.exists(directory.resolve(filename))) continue;
            Map<String, Object> doc = document(filename);
            // Services are user-editable endpoints. Never forward a key based on a filename alone.
            String url = string(doc, "url", "");
            if (!officialEndpoint(url, provider[1])) { if (bool(doc, "enabled", false)) warnings.add("Edited/unknown FoxGate endpoint kept for manual review: " + provider[0]); continue; }
            Map<String, Object> wrapper = new LinkedHashMap<>(); wrapper.put("service", doc);
            provider(wrapper, "service", provider[1], "enabled", Arrays.asList("proxycheck","vpnapi","ipqualityscore","iphub").contains(provider[1]) ? "key" : null);
        }
        track("modules/plus/geolocation.yml");
        if (Files.exists(directory.resolve("modules/plus/geolocation.yml")) && !string("key", "").trim().isEmpty()) {
            Map<String, Object> geo = document("modules/plus/geolocation.yml");
            if (bool(geo, "enable", false)) {
                String mode = string(geo, "country.blocker.type", "blacklist");
                if (!Arrays.asList("blacklist", "whitelist").contains(mode.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unknown FoxGate geo mode.");
                countries(list(geo, "country.blocker.list"), mode.equalsIgnoreCase("whitelist"));
                warnings.add("FoxGate PLUS selected from the configured license; country module exceptions and ASN allowlist semantics require separate review.");
                if (!list(geo, "asn.blocker.list").isEmpty() && !exportedRules) blockers.add("FoxGate PLUS ASN blocking rules need a complete local admin-rule export; conditional module exceptions are not guessed.");
            }
        }
        for (String module : Arrays.asList("modules/iprange.yml", "modules/ispfilter.yml")) {
            track(module);
            if (!Files.exists(directory.resolve(module))) continue;
            Map<String, Object> doc = document(module);
            if (bool(doc, "enable", false) && (!list(doc, "blocker.list").isEmpty() || !list(doc, "bypass.list").isEmpty()
                    || !list(doc, "blocker.lists.list").isEmpty()) && !exportedRules)
                blockers.add("FoxGate " + module + " has conditional/pattern admin rules. Supply a complete migration-rules.yml export rather than silently dropping/widening them.");
        }
        track("database.yml");
        if (Files.exists(directory.resolve("database.yml"))) {
            Map<String, Object> db = document("database.yml");
            if (bool(db, "database.whitelist_database", false) && !exportedRules) blockers.add("Export the FoxGate database whitelist locally before migration; no remote database is contacted.");
        }
    }
    private void proxyshield() {
        map("operation.mode", bool("detection.dry-run", false) ? "OBSERVE" : "ENFORCE");
        map("failure-policy.vpn", bool("settings.fail-closed", false) ? "CLOSED" : "OPEN");
        entries(input, "whitelist.ips", AccessRule.Effect.ALLOW); entries(input, "whitelist.players", AccessRule.Effect.ALLOW);
        entries(input, "blacklist.ips", AccessRule.Effect.DENY);
        if (bool("detection.asn.enabled", true)) for (String name : new String[]{"blocked", "allowed"}) for (Object raw : list(input, "detection.asn." + name)) {
            if (!(raw instanceof String) && !(raw instanceof Number)) throw new IllegalArgumentException("Invalid ASN list (values redacted).");
            selector("asn:" + raw, name.equals("blocked") ? AccessRule.Effect.DENY : AccessRule.Effect.ALLOW, AccessRule.Scope.VPN, "detection.asn." + name);
        }
        if (bool("country.enabled", false)) {
            String mode = string("country.mode", "blacklist"); if (!mode.equalsIgnoreCase("blacklist") && !mode.equalsIgnoreCase("whitelist")) throw new IllegalArgumentException("Unknown country mode.");
            countries(list(input, "country.codes"), mode.equalsIgnoreCase("whitelist"), true);
            map("failure-policy.geo", bool("country.block-unknown", false) ? "CLOSED" : "OPEN");
        }
        if (bool("detection.use-api", true)) for (String id : new String[]{"proxycheck","vpnapi","ipqualityscore","iphub","blackbox","ipquery","ip-api","zowi","ipcheck"})
            provider(input, "api.providers." + (id.equals("ipcheck") ? "ip-check" : id), id, "enabled", Arrays.asList("proxycheck","vpnapi","ipqualityscore","iphub").contains(id) ? "key" : null);
        warnings.add("ProxyShield consensus weights, rdns/heuristics, broad hosting blocks, trust history and Bedrock name bypass are not copied. Explicit IP/CIDR/UUID/ASN rules are retained.");
    }
    private void vpnguard() throws IOException {
        map("operation.mode", bool("join-enforcement.enabled", false) ? "ENFORCE" : "OBSERVE");
        map("behavior.vpn.notify-staff", bool("join-enforcement.notify-staff", true));
        for (String id : new String[]{"proxycheck", "vpnapi", "ip-api"}) provider(input, "providers." + id, id, "enabled", id.equals("ip-api") ? null : "key");
        if (!exportedRules) MigrationDatabases.sqlite(this, false);
        warnings.add("VPNGuard hosting/relay/compromised switches, min-providers-flagged and sequential login throttling are not transplanted into CG's failover.");
    }
    private void kauri() throws IOException {
        map("behavior.vpn.kick-player", bool("kickPlayers", true));
        map("behavior.vpn.notify-staff", bool("alerts.enabled", true));
        countries(list(input, "countries.list"), bool("countries.whitelist", true));
        if (!list(input, "prefixWhitelists").isEmpty()) warnings.add("KauriVPN prefixWhitelists are not imported: configure verified Floodgate/UUID rules, never trust player-name prefixes.");
        warnings.add("KauriVPN's funkemunky.cc license is not a ProxyCheck key. CG uses its own disclosed failover; the license and cached responses are not transferred.");
        if (bool("commands.enabled", false)) {
            warnings.add("Kauri custom commands need manual replacement; CG uses its configured kick/observe policy.");
            if (!bool("kickPlayers", true) && !exportedRules) blockers.add("Kauri custom commands are the only configured enforcement; review CG kicking explicitly with a complete local export.");
        }
        if (bool("database.enabled", true) && !exportedRules) {
            if (!string("database.type", "H2").equalsIgnoreCase("H2")) blockers.add("Export remote KauriVPN rules locally before migration; MySQL/Mongo are not contacted.");
            else MigrationDatabases.h2(this);
        }
    }
    private void advanced() throws IOException {
        map("behavior.vpn.kick-player", bool("Actions.Block.Enabled", true));
        map("behavior.vpn.notify-staff", bool("Actions.Notify.Enabled", false));
        if (!bool("Actions.Block.Enabled", true) && bool("Actions.Commands.Enabled", false) && !exportedRules)
            blockers.add("AdvancedAntiVPN custom commands are the only configured enforcement; review CG kicking explicitly with a complete local export.");
        for (String[] provider : new String[][]{{"ProxyCheck","proxycheck"},{"VPNAPI","vpnapi"},{"IPHub","iphub"},{"IPQualityScore","ipqualityscore"},{"IP-API","ip-api"}})
            provider(input, "Services." + provider[0], provider[1], "Enabled", provider[1].equals("ip-api") ? null : "Key");
        if (bool("GeoLite2.Enabled", false)) {
            String mode = string("GeoLite2.Mode", "WHITELIST"); if (!mode.equalsIgnoreCase("WHITELIST") && !mode.equalsIgnoreCase("BLACKLIST")) throw new IllegalArgumentException("Unknown GeoLite2 mode.");
            countries(list(input, "GeoLite2.Countries"), mode.equalsIgnoreCase("WHITELIST"), true);
            warnings.add("GeoLite2's local database/license is not a CG API key; country intent is retained with the disclosed ProxyCheck geo source.");
        }
        if (!exportedRules) {
            if (!string("Database.Type", "SQLITE").equalsIgnoreCase("SQLITE")) blockers.add("Export AdvancedAntiVPN remote admin rules locally first; database credentials are not copied or used.");
            else MigrationDatabases.sqlite(this, true);
        }
        warnings.add("AdvancedAntiVPN Flagged Threshold, fraud scores, connection-count limits and Aegis are not equivalent to CG failover/admission. Review them separately; do not silently weaken detection with threshold voting.");
    }
    private static boolean officialEndpoint(String url, String id) {
        try {
            java.net.URI uri = java.net.URI.create(url.replaceAll("(?i)\\{ip\\}|%ip%", "192.0.2.1").replaceAll("(?i)\\{key\\}|%key%", "key"));
            if (uri.getUserInfo() != null || uri.getHost() == null || !("https".equals(uri.getScheme()) || id.equals("ip-api") && "http".equals(uri.getScheme()))) return false;
            String expected = new HashMap<String, String>() {{ put("proxycheck","proxycheck.io"); put("vpnapi","vpnapi.io"); put("ipqualityscore","www.ipqualityscore.com"); put("iphub","v2.api.iphub.info"); put("blackbox","blackbox.ipinfo.app"); put("ipcheck","ip-check.net"); put("zowi","api.zowi.gay"); put("ipquery","api.ipquery.io"); put("ip-api","ip-api.com"); }}.get(id);
            return uri.getHost().equalsIgnoreCase(expected);
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
