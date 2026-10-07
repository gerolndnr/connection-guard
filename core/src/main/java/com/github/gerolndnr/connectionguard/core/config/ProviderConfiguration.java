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
    public final boolean cloudManagesPolicy;
    public final ArrayList<VpnProvider> providers = new ArrayList<>();
    public final List<String> keys = new ArrayList<>();
    public final Map<String, Integer> dayBudgets = new HashMap<>(), minuteBudgets = new HashMap<>();
    public final Map<String, String> healthIds = new HashMap<>();
    public final GuardSettings settings;
    public final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages;
    public final GeoProvider geo;
    public final int threshold, vpnTtl, geoTtl;
    public final boolean failover;
    public final int externalAttempts;
    public final Path dataPath;
    public final String cacheSignature;
    public final String cacheNamespace;
    public final LocalDataStore localStore;
    public final List<LocalSnapshot> localSnapshots;
    public final int localUpdateHours;
    public final IntelSettings intelSettings;
    public final IntelDataStore intelStore;
    public final IntelSnapshot intelSnapshot;
    public final boolean intelBootstrapPending;
    public final List<com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider> extensionProviders;
    public final com.github.gerolndnr.connectionguard.core.extensions.ObserverSettings observers;
    private final transient Function<String, Object> values;
    private final transient List<String> providerKeys;
    private final transient Path dataDirectory;
    public ProviderConfiguration(Function<String, Object> value, List<String> providerKeys) {
        this(value, providerKeys, null);
    }
    public ProviderConfiguration(Function<String, Object> value, List<String> providerKeys, Path dataDirectory) {
        this(value, providerKeys, dataDirectory, null);
    }
    public ProviderConfiguration(Function<String, Object> value, List<String> providerKeys, Path dataDirectory,
                                 com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages) {
        this(value, providerKeys, dataDirectory, messages, true);
    }
    /** Startup never parses saved Intel indexes on the platform's lifecycle thread. Reloads still validate. */
    public static ProviderConfiguration forStartup(Function<String, Object> value, List<String> providerKeys, Path dataDirectory,
                                 com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages) {
        return new ProviderConfiguration(value, providerKeys, dataDirectory, messages, false);
    }
    private ProviderConfiguration(Function<String, Object> value, List<String> providerKeys, Path dataDirectory,
                                 com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages, boolean loadIntel) {
        String language = com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.selection(value.apply("message-language"));
        this.messages = messages == null ? com.github.gerolndnr.connectionguard.core.messages.MessageCatalog.defaults(language) : messages;
        if (!this.messages.language().equals(language)) throw new IllegalArgumentException("Message draft does not match selected language (value redacted).");
        this.values = value; this.providerKeys = Collections.unmodifiableList(new ArrayList<>(providerKeys)); this.dataDirectory = dataDirectory;
        cloudManagesPolicy = com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.managesDecisionPolicy(dataDirectory);
        settings = GuardSettings.read(value, providerKeys);
        dataPath = dataDirectory;
        failover = settings.vpnFailover.enabled;
        externalAttempts = settings.vpnFailover.maxExternalAttempts;
        observers = new com.github.gerolndnr.connectionguard.core.extensions.ObserverSettings(value);
        LocalDataSettings local = new LocalDataSettings(value);
        localUpdateHours = local.updateHours;
        intelSettings = new IntelSettings(value);
        intelStore = intelSettings.enabled && dataDirectory != null ? new IntelDataStore(dataDirectory, intelSettings) : null;
        intelBootstrapPending = intelStore != null && !loadIntel;
        try { intelSnapshot = intelStore == null || intelBootstrapPending ? IntelSnapshot.missing(intelSettings) : intelStore.load(System.currentTimeMillis()); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Intel data invalid; previous configuration preserved (contents redacted)."); }
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
        threshold = failover ? 1 : GuardSettings.integer(value, "required-positive-flags", 1);
        vpnTtl = GuardSettings.integer(value, "provider.cache.expiration.vpn", 1440);
        geoTtl = GuardSettings.integer(value, "provider.cache.expiration.geo", 4320);
        String cache = GuardSettings.string(value, "provider.cache.type", "SQLite");
        // Never expose this private signature: it contains cache access settings.
        cacheSignature = cache.toLowerCase(Locale.ROOT) + "\n" + GuardSettings.string(value, "provider.cache.redis.hostname", "")
                + "\n" + GuardSettings.integer(value, "provider.cache.redis.port", 6379) + "\n"
                + GuardSettings.string(value, "provider.cache.redis.username", "") + "\n" + GuardSettings.string(value, "provider.cache.redis.password", "") + "\n" + GuardSettings.bool(value, "provider.cache.redis.tls", false);
        List<String> orderedKeys = new ArrayList<>(providerKeys);
        Map<String, Integer> sourceDays = new HashMap<>(), sourceMinutes = new HashMap<>();
        if (intelSettings.enabled) { keys.add(IntelSnapshot.ID); providers.add(new IntelVpnProvider(intelSnapshot)); }
        if (failover && local.vpnEnabled) for (LocalSnapshot snapshot : loaded) { keys.add("local." + snapshot.source.id); providers.add(new LocalVpnProvider(snapshot)); }
        for (String key : orderedKeys) {
            if (key.equals("local")) continue;
            String base = "provider.vpn." + key + ".";
            if (!GuardSettings.bool(value, base + "enabled", false)) continue;
            String apiKey = GuardSettings.string(value, base + "api-key", "");
            VpnProvider provider;
            switch (key) {
                case "proxycheck": provider = new ProxyCheckVpnProvider(apiKey, proxyCheckV3); break;
                case "ip-api": provider = new IpApiVpnProvider(); break;
                case "ipquery": provider = new IpQueryVpnProvider(); break;
                case "blackbox": provider = new BlackboxVpnProvider(GuardSettings.bool(value,base+"require-confirmation",true),intelSnapshot); break;
                case "ipcheck": provider = new IpCheckVpnProvider(); break;
                case "zowi": provider = new ZowiVpnProvider(); break;
                case "iphub": provider = new IpHubVpnProvider(apiKey); break;
                case "vpnapi": provider = new VpnApiVpnProvider(apiKey); break;
                case "ipqualityscore":
                    provider = new IpQualityScoreVpnProvider(apiKey, GuardSettings.integer(value, base + "strictness", 0),
                            GuardSettings.bool(value, base + "allow-public-access-points", true), GuardSettings.bool(value, base + "fast", true));
                    break;
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
            int day = GuardSettings.integer(value, base + "daily-budget", key.equals("proxycheck") ? apiKey.isEmpty() ? 100 : 1000 : key.equals("ipqualityscore") ? 30 : 0);
            int minute = GuardSettings.integer(value, base + "minute-budget", key.equals("ip-api") ? 45 : key.equals("ipqualityscore") ? 5
                    : key.equals("blackbox") || key.equals("ipcheck") || key.equals("zowi") ? 60 : 0);
            if (day < 0 || minute < 0) throw new IllegalArgumentException("Provider budgets must be nonnegative.");
            sourceDays.put(key, day); sourceMinutes.put(key, minute);
        }
        if (!failover && local.vpnEnabled) for (LocalSnapshot snapshot : loaded) { keys.add("local." + snapshot.source.id); providers.add(new LocalVpnProvider(snapshot)); }
        List<com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider> selected = new ArrayList<>();
        for (com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings.Source source : new com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings(value).sources) {
            com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider adapter = new com.github.gerolndnr.connectionguard.core.extensions.ExtensionVpnProvider(source);
            keys.add(adapter.sourceName()); providers.add(adapter); selected.add(adapter);
            sourceDays.put(adapter.sourceName(), source.dayBudget); sourceMinutes.put(adapter.sourceName(), source.minuteBudget);
        }
        if (failover) {
            List<String> originalKeys = new ArrayList<>(keys);
            List<VpnProvider> originalProviders = new ArrayList<>(providers);
            List<Integer> positions = new ArrayList<>();
            for (int i = 0; i < providers.size(); i++) positions.add(i);
            positions.sort(Comparator.comparingInt(i -> originalProviders.get(i) instanceof IntelVpnProvider ? -2 : settings.vpnFailover.rank(originalKeys.get(i), originalProviders.get(i).isLocal())));
            keys.clear(); providers.clear();
            for (int i : positions) { keys.add(originalKeys.get(i)); providers.add(originalProviders.get(i)); }
        }
        for (int i = 0; i < providers.size(); i++) {
            VpnProvider provider = providers.get(i);
            String key = keys.get(i);
            String name = provider.sourceName() == null ? provider.getClass().getSimpleName() : provider.sourceName();
            String id = name + (provider.stableSourceId() ? "" : "#" + i);
            dayBudgets.put(id, sourceDays.getOrDefault(key, 0)); minuteBudgets.put(id, sourceMinutes.getOrDefault(key, 0));
            healthIds.put(id, key.startsWith("extension.") || provider.isLocal() ? key : "vpn." + key);
        }
        extensionProviders = Collections.unmodifiableList(selected);
        String geoService = GuardSettings.string(value, "provider.geo.service", "IP-API");
        com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient sharedProxyCheck = providers.stream()
                .filter(p -> p instanceof ProxyCheckVpnProvider).map(p -> ((ProxyCheckVpnProvider) p).client()).findFirst().orElse(null);
        geo = geoService.equalsIgnoreCase("Disabled") ? null : geoService.equalsIgnoreCase("Local")
                ? new LocalGeoProvider(loaded.stream().filter(snapshot -> snapshot.source.kind == LocalSource.Kind.GEO).findFirst().get(),
                    loaded.stream().filter(snapshot -> snapshot.source.kind == LocalSource.Kind.ASN).findFirst().orElse(null))
                : geoService.equalsIgnoreCase("IP-API") ? new IpApiGeoProvider()
                : new ProxyCheckGeoProvider(GuardSettings.string(value, "provider.vpn.proxycheck.api-key", ""), proxyCheckV3, sharedProxyCheck);
        String id = geo == null ? "disabled" : geo.getClass().getSimpleName();
        int day = GuardSettings.integer(value, "provider.geo.daily-budget", geo instanceof ProxyCheckGeoProvider
                ? GuardSettings.string(value, "provider.vpn.proxycheck.api-key", "").isEmpty() ? 100 : 1000 : 0);
        int minute = GuardSettings.integer(value, "provider.geo.minute-budget", geo instanceof IpApiGeoProvider ? 45 : 0);
        if (day < 0 || minute < 0) throw new IllegalArgumentException("Geo budgets must be nonnegative.");
        if (geo != null) { dayBudgets.put(id, day); minuteBudgets.put(id, minute); }
        try {
            String input = "schema11-proxy-blackbox-confirmation:" + failover + ":" + externalAttempts + ":" + threshold + ":" + keys + ":" + new com.google.gson.Gson().toJson(providers)
                    + ":" + id + ":" + new com.google.gson.Gson().toJson(geo);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte part : hash) hex.append(String.format("%02x", part & 255));
            cacheNamespace = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    /** Read a complete new local generation from the already active configuration, without editing YAML. */
    public ProviderConfiguration refreshLocal() { return new ProviderConfiguration(values, providerKeys, dataDirectory, messages); }
}
