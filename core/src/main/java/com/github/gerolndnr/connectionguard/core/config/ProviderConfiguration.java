package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.geo.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.vpn.custom.CustomVpnProvider;
import java.util.*;
import java.util.function.Function;
import java.net.URI;

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
    public ProviderConfiguration(Function<String, Object> value, List<String> providerKeys) {
        settings = GuardSettings.read(value, providerKeys);
        threshold = GuardSettings.integer(value, "required-positive-flags", 1);
        vpnTtl = GuardSettings.integer(value, "provider.cache.expiration.vpn", 1440);
        geoTtl = GuardSettings.integer(value, "provider.cache.expiration.geo", 4320);
        String cache = GuardSettings.string(value, "provider.cache.type", "SQLite");
        // Never expose this private signature: it contains cache access settings.
        cacheSignature = cache.toLowerCase(Locale.ROOT) + "\n" + GuardSettings.string(value, "provider.cache.redis.hostname", "")
                + "\n" + GuardSettings.integer(value, "provider.cache.redis.port", 6379) + "\n"
                + GuardSettings.string(value, "provider.cache.redis.username", "") + "\n" + GuardSettings.string(value, "provider.cache.redis.password", "") + "\n" + GuardSettings.bool(value, "provider.cache.redis.tls", false);
        for (String key : providerKeys) {
            String base = "provider.vpn." + key + ".";
            if (!GuardSettings.bool(value, base + "enabled", false)) continue;
            String apiKey = GuardSettings.string(value, base + "api-key", "");
            VpnProvider provider;
            switch (key) {
                case "proxycheck": provider = new ProxyCheckVpnProvider(apiKey); break;
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
                    provider = new CustomVpnProvider(method, url, requestHeaders,
                            GuardSettings.string(value, base + "request-body-type", "application/json"), GuardSettings.string(value, base + "request-body", ""),
                            responseType, field, fieldType, GuardSettings.string(value, base + "response-format.is-vpn-field.string-options.is-vpn-string", ""),
                            GuardSettings.string(value, base + "response-format.vpn-provider-field.field-name", ""));
            }
            keys.add(key); providers.add(provider);
            String id = provider.getClass().getSimpleName() + "#" + (providers.size() - 1);
            int day = GuardSettings.integer(value, base + "daily-budget", key.equals("proxycheck") ? apiKey.isEmpty() ? 100 : 1000 : 0);
            int minute = GuardSettings.integer(value, base + "minute-budget", key.equals("ip-api") ? 45 : 0);
            if (day < 0 || minute < 0) throw new IllegalArgumentException("Provider budgets must be nonnegative.");
            dayBudgets.put(id, day); minuteBudgets.put(id, minute);
        }
        geo = GuardSettings.string(value, "provider.geo.service", "IP-API").equalsIgnoreCase("IP-API") ? new IpApiGeoProvider()
                : new ProxyCheckGeoProvider(GuardSettings.string(value, "provider.vpn.proxycheck.api-key", ""));
        String id = geo.getClass().getSimpleName();
        int day = GuardSettings.integer(value, "provider.geo.daily-budget", geo instanceof ProxyCheckGeoProvider
                ? GuardSettings.string(value, "provider.vpn.proxycheck.api-key", "").isEmpty() ? 100 : 1000 : 0);
        int minute = GuardSettings.integer(value, "provider.geo.minute-budget", geo instanceof IpApiGeoProvider ? 45 : 0);
        if (day < 0 || minute < 0) throw new IllegalArgumentException("Geo budgets must be nonnegative.");
        dayBudgets.put(id, day); minuteBudgets.put(id, minute);
        try {
            String input = "schema2:" + threshold + ":" + keys + ":" + new com.google.gson.Gson().toJson(providers)
                    + ":" + geo.getClass().getSimpleName() + ":" + new com.google.gson.Gson().toJson(geo);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte part : hash) hex.append(String.format("%02x", part & 255));
            cacheNamespace = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
}
