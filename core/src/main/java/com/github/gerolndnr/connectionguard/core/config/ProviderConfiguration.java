package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.geo.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.vpn.custom.CustomVpnProvider;
import java.util.*;
import java.util.function.Function;
import java.net.URI;
import java.nio.file.Path;
import com.github.gerolndnr.connectionguard.core.local.*;

/** Build a complete provider draft before mutating active state. No network or file writes. */
public final class ProviderConfiguration {
    public final ArrayList<VpnProvider> providers = new ArrayList<>();
    public final List<String> keys = new ArrayList<>();
    public final Map<String, Integer> dayBudgets = new HashMap<>(), minuteBudgets = new HashMap<>();
    public final GuardSettings settings;
    public final GeoProvider geo;
    public final int threshold, vpnTtl, geoTtl;
    public final String cacheSignature;
    public final String cacheNamespace;
    public final LocalDataStore localStore;
    public final List<LocalSnapshot> localSnapshots;
    public final int localUpdateHours;
    public final List<com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider> extensionProviders;
    public final com.github.gerolndnr.connectionguard.core.extensions.ObserverSettings observers;
    private final transient Function<String, Object> values;
    private final transient List<String> providerKeys;
    private final transient Path dataDirectory;
    public ProviderConfiguration(Function<String, Object> value, List<String> providerKeys) {
        this(value, providerKeys, null);
    }
    public ProviderConfiguration(Function<String, Object> value, List<String> providerKeys, Path dataDirectory) {
        this.values = value; this.providerKeys = Collections.unmodifiableList(new ArrayList<>(providerKeys)); this.dataDirectory = dataDirectory;
        settings = GuardSettings.read(value, providerKeys);
        observers = new com.github.gerolndnr.connectionguard.core.extensions.ObserverSettings(value);
        LocalDataSettings local = new LocalDataSettings(value);
        localUpdateHours = local.updateHours;
        localStore = local.vpnEnabled || local.geoEnabled ? new LocalDataStore(dataDirectory, local.sources) : null;
        List<LocalSnapshot> loaded = new ArrayList<>();
        if (localStore != null) for (LocalSource source : local.sources) {
            try { loaded.add(localStore.load(source.id, System.currentTimeMillis())); }
            catch (java.io.IOException invalid) { throw new IllegalArgumentException("Local data is invalid; previous settings preserved (values redacted)."); }
        }
        localSnapshots = Collections.unmodifiableList(loaded);
        String proxyCheckVersion = GuardSettings.string(value, "provider.vpn.proxycheck.api-version", "v2");
        if (!proxyCheckVersion.equalsIgnoreCase("v2") && !proxyCheckVersion.equalsIgnoreCase("v3")) throw new IllegalArgumentException("ProxyCheck api-version must be v2 or v3.");
        boolean proxyCheckV3 = proxyCheckVersion.equalsIgnoreCase("v3");
        threshold = GuardSettings.integer(value, "required-positive-flags", 1);
        vpnTtl = GuardSettings.integer(value, "provider.cache.expiration.vpn", 1440);
        geoTtl = GuardSettings.integer(value, "provider.cache.expiration.geo", 4320);
        String cache = GuardSettings.string(value, "provider.cache.type", "SQLite");
        // Never expose this private signature: it contains cache access settings.
        cacheSignature = cache.toLowerCase(Locale.ROOT) + "\n" + GuardSettings.string(value, "provider.cache.redis.hostname", "")
                + "\n" + GuardSettings.integer(value, "provider.cache.redis.port", 6379) + "\n"
                + GuardSettings.string(value, "provider.cache.redis.username", "") + "\n" + GuardSettings.string(value, "provider.cache.redis.password", "") + "\n" + GuardSettings.bool(value, "provider.cache.redis.tls", false);
        for (String key : providerKeys) {
            if (key.equals("local")) continue;
            String base = "provider.vpn." + key + ".";
            if (!GuardSettings.bool(value, base + "enabled", false)) continue;
            String apiKey = GuardSettings.string(value, base + "api-key", "");
            VpnProvider provider;
            switch (key) {
                case "proxycheck": provider = new ProxyCheckVpnProvider(apiKey, proxyCheckV3); break;
                case "ip-api": provider = new IpApiVpnProvider(); break;
                case "iphub": provider = new IpHubVpnProvider(apiKey); break;
                case "vpnapi": provider = new VpnApiVpnProvider(apiKey); break;
                default:
                    String method = GuardSettings.string(value, base + "request-type", "GET");
                    if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("POST")) throw new IllegalArgumentException("Custom request-type must be GET or POST.");
                    String url = GuardSettings.string(value, base + "request-url", "");
                    try {
                        URI uri = URI.create(url.replace("%IP%", "192.0.2.1"));
                        if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))) throw new IllegalArgumentException();
                    } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Custom provider URL is invalid (value redacted)."); }
                    Object headers = value.apply(base + "request-header");
                    List<String> requestHeaders = new ArrayList<>();
                    if (headers != null && !(headers instanceof List)) throw new IllegalArgumentException("Custom request-header must be a list (values redacted).");
                    if (headers != null) for (Object header : (List<?>) headers) {
                        if (!(header instanceof String) || !((String) header).contains(":")) throw new IllegalArgumentException("Custom header is invalid (value redacted).");
                        requestHeaders.add((String) header);
                    }
                    String responseType = GuardSettings.string(value, base + "response-type", "application/json");
                    String fieldType = GuardSettings.string(value, base + "response-format.is-vpn-field.field-type", "BOOLEAN");
                    if (!responseType.equalsIgnoreCase("application/json") || (!fieldType.equalsIgnoreCase("BOOLEAN") && !fieldType.equalsIgnoreCase("STRING")))
                        throw new IllegalArgumentException("Custom response type/flag type is invalid.");
                    String field = GuardSettings.string(value, base + "response-format.is-vpn-field.field-name", "");
                    if (field.isEmpty()) throw new IllegalArgumentException("Custom VPN flag field is required.");
                    Map<String, String> details = new LinkedHashMap<>();
                    for (String name : Arrays.asList("vpn", "proxy", "tor", "relay", "hosting", "asn", "isp", "operator", "country", "risk", "confidence")) {
                        String path = GuardSettings.string(value, base + "response-format.details." + name, "");
                        if (!path.isEmpty()) {
                            if (path.length() > 200 || Arrays.stream(path.split("#", -1)).anyMatch(String::isEmpty)) throw new IllegalArgumentException("Invalid custom metadata field path.");
                            details.put(name, path);
                        }
                    }
                    provider = new CustomVpnProvider(method, url, requestHeaders,
                            GuardSettings.string(value, base + "request-body-type", "application/json"), GuardSettings.string(value, base + "request-body", ""),
                            responseType, field, fieldType, GuardSettings.string(value, base + "response-format.is-vpn-field.string-options.is-vpn-string", ""),
                            GuardSettings.string(value, base + "response-format.vpn-provider-field.field-name", ""), details);
            }
            keys.add(key); providers.add(provider);
            String id = provider.getClass().getSimpleName() + "#" + (providers.size() - 1);
            int day = GuardSettings.integer(value, base + "daily-budget", key.equals("proxycheck") ? apiKey.isEmpty() ? 100 : 1000 : 0);
            int minute = GuardSettings.integer(value, base + "minute-budget", key.equals("ip-api") ? 45 : 0);
            if (day < 0 || minute < 0) throw new IllegalArgumentException("Provider budgets must be nonnegative.");
            dayBudgets.put(id, day); minuteBudgets.put(id, minute);
        }
        if (local.vpnEnabled) for (LocalSnapshot snapshot : loaded) { keys.add("local." + snapshot.source.id); providers.add(new LocalVpnProvider(snapshot)); }
        List<com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider> selected = new ArrayList<>();
        for (com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings.Source source : new com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings(value).sources) {
            com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider adapter = new com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider(source);
            keys.add(adapter.sourceName()); providers.add(adapter); selected.add(adapter);
            dayBudgets.put(adapter.sourceName(), source.dayBudget); minuteBudgets.put(adapter.sourceName(), source.minuteBudget);
        }
        extensionProviders = Collections.unmodifiableList(selected);
        String geoService = GuardSettings.string(value, "provider.geo.service", "IP-API");
        geo = geoService.equalsIgnoreCase("Disabled") ? null : geoService.equalsIgnoreCase("Local")
                ? new LocalGeoProvider(loaded.stream().filter(snapshot -> snapshot.source.kind == LocalSource.Kind.GEO).findFirst().get(),
                    loaded.stream().filter(snapshot -> snapshot.source.kind == LocalSource.Kind.ASN).findFirst().orElse(null))
                : geoService.equalsIgnoreCase("IP-API") ? new IpApiGeoProvider()
                : new ProxyCheckGeoProvider(GuardSettings.string(value, "provider.vpn.proxycheck.api-key", ""), proxyCheckV3);
        String id = geo == null ? "disabled" : geo.getClass().getSimpleName();
        int day = GuardSettings.integer(value, "provider.geo.daily-budget", geo instanceof ProxyCheckGeoProvider
                ? GuardSettings.string(value, "provider.vpn.proxycheck.api-key", "").isEmpty() ? 100 : 1000 : 0);
        int minute = GuardSettings.integer(value, "provider.geo.minute-budget", geo instanceof IpApiGeoProvider ? 45 : 0);
        if (day < 0 || minute < 0) throw new IllegalArgumentException("Geo budgets must be nonnegative.");
        if (geo != null) { dayBudgets.put(id, day); minuteBudgets.put(id, minute); }
        try {
            String input = "schema6-extensions:" + threshold + ":" + keys + ":" + new com.google.gson.Gson().toJson(providers)
                    + ":" + id + ":" + new com.google.gson.Gson().toJson(geo);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte part : hash) hex.append(String.format("%02x", part & 255));
            cacheNamespace = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    /** Read a complete new local generation from the already active configuration, without editing YAML. */
    public ProviderConfiguration refreshLocal() { return new ProviderConfiguration(values, providerKeys, dataDirectory); }
}
