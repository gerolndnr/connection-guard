package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.lookup.LookupSettings;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Validated settings. Missing new settings preserve the legacy operator's enforcement choice. */
public final class GuardSettings {
    public enum FailurePolicy { OPEN, CLOSED, OBSERVE }
    public final LookupSettings lookup;
    public final com.github.gerolndnr.connectionguard.core.webhook.WebhookSettings webhooks;
    public final com.github.gerolndnr.connectionguard.core.admission.AdmissionSettings admission;
    public final com.github.gerolndnr.connectionguard.core.extensions.AdmissionHookSettings admissionHooks;
    public final boolean observe;
    public final boolean trustForwardedIdentity;
    public final boolean nativeFloodgateIdentity;
    public final boolean nativePaperForwardingIdentity;
    public final FailurePolicy vpnFailure;
    public final FailurePolicy geoFailure;
    public final boolean kickVpn;
    public final boolean kickGeo;
    public final boolean geoWhitelist;
    public final List<String> countries;
    public final List<String> warnings;
    private GuardSettings(LookupSettings lookup, com.github.gerolndnr.connectionguard.core.admission.AdmissionSettings admission, boolean observe, boolean trust, boolean floodgate, boolean paperForwarding, com.github.gerolndnr.connectionguard.core.extensions.AdmissionHookSettings hooks, FailurePolicy vpn,
                          FailurePolicy geo, List<String> warnings, com.github.gerolndnr.connectionguard.core.webhook.WebhookSettings webhooks,
                          boolean kickVpn, boolean kickGeo, boolean geoWhitelist, List<String> countries) {
        this.webhooks = webhooks;
        this.lookup = lookup; this.admission = admission; this.observe = observe; this.trustForwardedIdentity = trust; this.nativeFloodgateIdentity = floodgate; this.nativePaperForwardingIdentity = paperForwarding;
        this.admissionHooks = hooks;
        this.vpnFailure = vpn; this.geoFailure = geo; this.warnings = java.util.Collections.unmodifiableList(warnings);
        this.kickVpn = kickVpn; this.kickGeo = kickGeo; this.geoWhitelist = geoWhitelist;
        this.countries = java.util.Collections.unmodifiableList(new ArrayList<>(countries));
    }
    public static GuardSettings read(Function<String, Object> value, List<String> providerKeys) {
        com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.selection(value.apply("message-language"));
        List<String> warnings = new ArrayList<>();
        LookupSettings defaults = LookupSettings.defaults();
        LookupSettings limits = new LookupSettings(integer(value, "lookup.deadline-ms", (int) defaults.deadlineMillis),
                integer(value, "lookup.http-timeout-ms", (int) defaults.httpTimeoutMillis), integer(value, "lookup.workers", defaults.workers),
                integer(value, "lookup.queue-capacity", defaults.queueCapacity), integer(value, "lookup.max-inflight", defaults.maxInflight),
                integer(value, "lookup.circuit.failures", defaults.circuitFailures), integer(value, "lookup.circuit.pause-ms", (int) defaults.circuitPauseMillis));
        String mode = string(value, "operation.mode", "ENFORCE").toUpperCase(Locale.ROOT);
        if (!mode.equals("OBSERVE") && !mode.equals("ENFORCE")) throw new IllegalArgumentException("operation.mode must be OBSERVE or ENFORCE.");
        String strategy = string(value, "provider.vpn-strategy", "CONSENSUS");
        if (!strategy.equalsIgnoreCase("CONSENSUS") && !strategy.equalsIgnoreCase("FAILOVER")) throw new IllegalArgumentException("provider.vpn-strategy must be CONSENSUS or FAILOVER.");
        int attempts = integer(value, "provider.max-external-attempts", 3);
        if (attempts < 1 || attempts > 16) throw new IllegalArgumentException("provider.max-external-attempts must be 1..16.");
        if (strategy.equalsIgnoreCase("FAILOVER") && integer(value, "required-positive-flags", 1) != 1) throw new IllegalArgumentException("FAILOVER requires required-positive-flags: 1 (no consensus).");
        int enabled = 0;
        for (String key : providerKeys) if (bool(value, "provider.vpn." + key + ".enabled", false)) {
            if (key.equals("local")) continue;
            enabled++;
            if (enabled > 16) throw new IllegalArgumentException("At most 16 VPN providers may be enabled.");
            if ((key.equals("iphub") || key.equals("vpnapi") || key.equals("ipqualityscore")) && string(value, "provider.vpn." + key + ".api-key", "").trim().isEmpty()) {
                throw new IllegalArgumentException("Enabled " + key + " requires an API key.");
            }
        }
        com.github.gerolndnr.connectionguard.core.local.LocalDataSettings local = new com.github.gerolndnr.connectionguard.core.local.LocalDataSettings(value);
        com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings extensions = new com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings(value);
        enabled += local.votingProviders() + extensions.voting();
        int allSources = enabled + (local.vpnEnabled ? local.sources.size() - local.votingProviders() : 0) + extensions.sources.size() - extensions.voting();
        if (allSources > 16) throw new IllegalArgumentException("At most 16 VPN/enrichment providers may be enabled.");
        int threshold = integer(value, "required-positive-flags", 1);
        if (threshold < 1 || threshold > 16 || (enabled > 0 && threshold > enabled)) throw new IllegalArgumentException("required-positive-flags must be 1..enabled voting provider count (maximum 16).");
        if (enabled == 0) warnings.add("No VPN provider enabled: VPN classification is UNKNOWN.");
        if (bool(value, "behavior.vpn.use-permission-exemption", false) || bool(value, "behavior.geo.use-permission-exemption", false)) {
            if (!CGLuckPermsHelper.isAvailable()) warnings.add("Permission exemptions enabled but LuckPerms is unavailable; checks remain active.");
        }
        String cache = string(value, "provider.cache.type", "SQLite");
        if (!cache.equalsIgnoreCase("SQLite") && !cache.equalsIgnoreCase("Redis") && !cache.equalsIgnoreCase("Memory") && !cache.equalsIgnoreCase("Disabled")) {
            throw new IllegalArgumentException("provider.cache.type must be SQLite, Redis, Memory or Disabled.");
        }
        if (cache.equalsIgnoreCase("Redis")) {
            String hostname = string(value, "provider.cache.redis.hostname", "");
            if (hostname.isEmpty() || hostname.length() > 253 || !hostname.matches("[A-Za-z0-9_.:%-]+"))
                throw new IllegalArgumentException("Redis requires a hostname or literal address, without a URL scheme (value redacted).");
            int port = integer(value, "provider.cache.redis.port", 6379);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Redis port must be 1..65535.");
            if (string(value, "provider.cache.redis.username", "").length() > 256
                    || string(value, "provider.cache.redis.password", "").length() > 4096)
                throw new IllegalArgumentException("Redis credential is too large (value redacted).");
            bool(value, "provider.cache.redis.tls", false);
        }
        if (integer(value, "provider.cache.expiration.vpn", 1440) < 1 || integer(value, "provider.cache.expiration.geo", 4320) < 1) {
            throw new IllegalArgumentException("Cache expiration must be a positive number of minutes.");
        }
        String geo = string(value, "behavior.geo.type", "BLACKLIST");
        if (!geo.equalsIgnoreCase("BLACKLIST") && !geo.equalsIgnoreCase("WHITELIST")) throw new IllegalArgumentException("Geo type must be BLACKLIST or WHITELIST.");
        List<String> countries = new ArrayList<>();
        Object countryList = value.apply("behavior.geo.list");
        if (countryList != null) {
            if (!(countryList instanceof List) || ((List<?>) countryList).size() > 250)
                throw new IllegalArgumentException("behavior.geo.list must be a list of at most 250 uppercase country codes.");
            for (Object country : (List<?>) countryList) {
                if (!(country instanceof String) || !((String) country).matches("[A-Z]{2}"))
                    throw new IllegalArgumentException("behavior.geo.list requires uppercase two-letter country codes (value redacted).");
                if (!countries.contains(country)) countries.add((String) country);
            }
        }
        String service = string(value, "provider.geo.service", "IP-API");
        if (!service.equalsIgnoreCase("IP-API") && !service.equalsIgnoreCase("ProxyCheck") && !service.equalsIgnoreCase("Local") && !service.equalsIgnoreCase("Disabled")) throw new IllegalArgumentException("Geo service must be IP-API, ProxyCheck, Local or Disabled.");
        if (service.equalsIgnoreCase("IP-API") || bool(value, "provider.vpn.ip-api.enabled", false)) {
            warnings.add("IP-API free uses HTTP and is restricted to non-commercial use; check provider terms.");
        }
        com.github.gerolndnr.connectionguard.core.webhook.WebhookSettings webhooks = new com.github.gerolndnr.connectionguard.core.webhook.WebhookSettings(value);
        if (bool(value, "provider.vpn.ipqualityscore.enabled", false)) {
            warnings.add("IPQualityScore budgets count local requests and reset on restart; they are not the account monthly balance. Use your own key and check current provider terms.");
            if (integer(value, "provider.vpn.ipqualityscore.strictness", 0) >= 2)
                warnings.add("IPQualityScore strictness 2+ increases false-positive risk; validate in OBSERVE before enforcement.");
        }
        boolean trust = bool(value, "identity.trust-forwarded-uuid", false);
        if (trust) warnings.add("Forwarded UUID trust enabled: restrict backend access and verify proxy/Floodgate forwarding; a public IP is insufficient proof.");
        com.github.gerolndnr.connectionguard.core.admission.AdmissionSettings admission = new com.github.gerolndnr.connectionguard.core.admission.AdmissionSettings(value);
        if (admission.enabled) warnings.add("Overload limits skip detection on refused admissions; only overload.deny-connections may temporarily deny (OBSERVE suppresses denial). Provider failure policies do not change this choice.");
        boolean floodgate = bool(value, "identity.floodgate.enabled", false);
        if (floodgate && !com.github.gerolndnr.connectionguard.core.identity.FloodgateIdentity.isAvailable())
            warnings.add("Native Floodgate identity selected but the canonical API is unavailable; no native exemption is granted.");
        boolean paperForwarding = bool(value, "identity.paper-modern-forwarding.enabled", false);
        if (paperForwarding) warnings.add("Native Paper modern forwarding selected: only qualified native pre-login profile paths supply gateway authority; protect the proxy secret and backend access. This is not independent account authentication.");
        return new GuardSettings(limits, admission, mode.equals("OBSERVE"), trust, floodgate, paperForwarding,
                new com.github.gerolndnr.connectionguard.core.extensions.AdmissionHookSettings(value),
                policy(value, "failure-policy.vpn"), policy(value, "failure-policy.geo"), warnings, webhooks,
                bool(value, "behavior.vpn.kick-player", false), bool(value, "behavior.geo.kick-player", false),
                geo.equalsIgnoreCase("WHITELIST"), countries);
    }
    public static GuardSettings defaults() { return read(path -> null, java.util.Collections.emptyList()); }
    private static FailurePolicy policy(Function<String, Object> value, String path) {
        try { return FailurePolicy.valueOf(string(value, path, "OPEN").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(path + " must be OPEN, CLOSED or OBSERVE."); }
    }
    public static String string(Function<String, Object> value, String path, String fallback) {
        Object raw = value.apply(path);
        if (raw == null) return fallback;
        if (!(raw instanceof String)) throw new IllegalArgumentException(path + " must be text (value redacted).");
        return (String) raw;
    }
    public static boolean bool(Function<String, Object> value, String path, boolean fallback) {
        Object raw = value.apply(path);
        if (raw == null) return fallback;
        if (!(raw instanceof Boolean)) throw new IllegalArgumentException(path + " must be true or false.");
        return (Boolean) raw;
    }
    public static int integer(Function<String, Object> value, String path, int fallback) {
        Object raw = value.apply(path);
        if (raw == null) return fallback;
        if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).intValue()) throw new IllegalArgumentException(path + " must be an integer.");
        return ((Number) raw).intValue();
    }
}
