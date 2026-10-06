package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.github.gerolndnr.connectionguard.core.http.ProviderAddresses;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import java.util.Map;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckVpnProvider implements VpnProvider {
    private final String apiKey;
    private final boolean v3;
    private final transient com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient client;

    public ProxyCheckVpnProvider(String apiKey) { this(apiKey, false); }
    public ProxyCheckVpnProvider(String apiKey, boolean v3) { this(apiKey, v3, new com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient(apiKey)); }
    public ProxyCheckVpnProvider(String apiKey, boolean v3, com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient client) {
        this.apiKey = apiKey; this.v3 = v3; this.client = java.util.Objects.requireNonNull(client);
    }
    public com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient client() { return client; }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return getVpnResult(ipAddress, com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().getSettings().deadlineMillis, () -> {});
    }
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip, long remaining, Runnable attempted) {
        return com.github.gerolndnr.connectionguard.core.ConnectionGuard.proxyCheckResponse(client, ip, false, remaining, attempted)
                .thenApply(answer -> answer.flatMap(json -> parse(ip, json)));
    }

    public static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) {
            if (status.equalsIgnoreCase("denied") || status.equalsIgnoreCase("denied access")) throw new com.github.gerolndnr.connectionguard.core.lookup.LookupException(ProviderHttp.exhaustedDailyQuota(json) ? com.github.gerolndnr.connectionguard.core.lookup.FailureReason.BUDGET_EXHAUSTED : com.github.gerolndnr.connectionguard.core.lookup.FailureReason.RATE_LIMIT, ProviderHttp.exhaustedDailyQuota(json) ? ProviderHttp.untilNextDay() : 60000);
            return Optional.empty();
        }
        JsonObject address = ProviderAddresses.response(json, ipAddress);
        String proxy = ProviderHttp.string(address, "proxy");
        if (!proxy.equalsIgnoreCase("yes") && !proxy.equalsIgnoreCase("no")) return Optional.empty();
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        String type = DetectionFields.text(address, "type");
        if (type != null) {
            // v2 reports one type. Never infer that all other types are false.
            String label = type.toUpperCase(Locale.ROOT);
            if (label.equals("VPN") || label.equals("TOR") || label.equals("HOSTING")) types.put(DetectionDetails.Type.valueOf(label), true);
            else if (label.startsWith("SOCKS") || label.equals("HTTP") || label.equals("HTTPS") || label.equals("SHADOWSOCKS") || label.equals("OPENVPN")) types.put(DetectionDetails.Type.PROXY, true);
        }
        // Hosting is allocation evidence, not an explicit VPN/proxy classification.
        VpnResult result = new VpnResult(ipAddress, proxy.equalsIgnoreCase("yes") && !"HOSTING".equalsIgnoreCase(type));
        result.setDetails(new DetectionDetails(types, DetectionFields.asn(address, "asn", false), DetectionFields.text(address, "provider"),
                null, DetectionFields.text(address, "isocode"), DetectionFields.score(address, "risk"), null));
        return Optional.of(result);
    }
    static Optional<VpnResult> parseV3(String ipAddress, JsonObject json) {
        String status = ProviderHttp.string(json, "status");
        if (!status.equalsIgnoreCase("ok") && !status.equalsIgnoreCase("warning")) {
            if (status.equalsIgnoreCase("denied") || status.equalsIgnoreCase("denied access")) throw new com.github.gerolndnr.connectionguard.core.lookup.LookupException(ProviderHttp.exhaustedDailyQuota(json) ? com.github.gerolndnr.connectionguard.core.lookup.FailureReason.BUDGET_EXHAUSTED : com.github.gerolndnr.connectionguard.core.lookup.FailureReason.RATE_LIMIT, ProviderHttp.exhaustedDailyQuota(json) ? ProviderHttp.untilNextDay() : 60000);
            return Optional.empty();
        }
        JsonObject address = ProviderAddresses.response(json, ipAddress);
        JsonObject detections = DetectionFields.object(address, "detections");
        Boolean anonymous = DetectionFields.bool(detections, "anonymous");
        JsonObject network = DetectionFields.object(address, "network");
        JsonObject operator = DetectionFields.object(address, "operator");
        String operatorName = DetectionFields.text(operator, "name");
        Map<DetectionDetails.Type, Boolean> types = DetectionFields.types(detections, DetectionDetails.Type.VPN, DetectionDetails.Type.PROXY, DetectionDetails.Type.TOR, DetectionDetails.Type.HOSTING);
        boolean vpnService = vpnOperator(operator);
        boolean hostingOnly = Boolean.TRUE.equals(types.get(DetectionDetails.Type.HOSTING)) && !Boolean.TRUE.equals(types.get(DetectionDetails.Type.VPN))
                && !Boolean.TRUE.equals(types.get(DetectionDetails.Type.PROXY)) && !Boolean.TRUE.equals(types.get(DetectionDetails.Type.TOR)) && !vpnService;
        boolean positive = Boolean.TRUE.equals(anonymous) && !hostingOnly || Boolean.TRUE.equals(types.get(DetectionDetails.Type.VPN))
                || Boolean.TRUE.equals(types.get(DetectionDetails.Type.PROXY)) || Boolean.TRUE.equals(types.get(DetectionDetails.Type.TOR)) || vpnService;
        // A hosting allocation alone is review evidence. An operator VPN service is concrete detection evidence.
        // Keep the provider's explicit flags verbatim, even when operator evidence determines the verdict.
        if (!positive && anonymous == null && !(types.containsKey(DetectionDetails.Type.VPN) && types.containsKey(DetectionDetails.Type.PROXY) && types.containsKey(DetectionDetails.Type.TOR))) return Optional.empty();
        VpnResult result = new VpnResult(ipAddress, positive, Optional.ofNullable(operatorName));
        result.setDetails(new DetectionDetails(types, DetectionFields.asn(network, "asn", false),
                DetectionFields.text(network, "provider"), operatorName,
                DetectionFields.text(DetectionFields.object(address, "location"), "isocode"),
                DetectionFields.score(address, "risk"), DetectionFields.score(detections, "confidence")));
        return Optional.of(result);
    }
    private static boolean vpnOperator(JsonObject operator) {
        if (operator == null) return false;
        com.google.gson.JsonElement services = operator.get("services");
        if (services == null || services.isJsonNull()) return false;
        if (!services.isJsonArray() || services.getAsJsonArray().size() > 32) throw new IllegalArgumentException("Invalid operator services.");
        boolean vpn = false;
        for (com.google.gson.JsonElement service : services.getAsJsonArray()) {
            if (!service.isJsonPrimitive() || !service.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid operator service.");
            String value = service.getAsString();
            if (value.length() > 100) throw new IllegalArgumentException("Invalid operator service.");
            vpn |= value.equals("datacenter_vpns") || value.equals("residential_vpns") || value.equals("mobile_vpns");
        }
        return vpn;
    }
}
