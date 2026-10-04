package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.*;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.google.gson.JsonObject;
import okhttp3.HttpUrl;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Native IPQS adapter. The operator key travels only in a header to a fixed HTTPS endpoint. */
public final class IpQualityScoreVpnProvider implements VpnProvider {
    private static final HttpUrl ENDPOINT = HttpUrl.get("https://ipqualityscore.com/api/json/ip");
    private final String apiKey;
    private final int strictness;
    private final boolean allowPublicAccessPoints, fast;
    private final transient HttpUrl fixtureEndpoint;

    public IpQualityScoreVpnProvider(String apiKey, int strictness, boolean allowPublicAccessPoints, boolean fast) {
        this(apiKey, strictness, allowPublicAccessPoints, fast, null);
    }
    /** Package-private fixture seam; production configuration cannot redirect operator keys. */
    IpQualityScoreVpnProvider(String apiKey, int strictness, boolean allowPublicAccessPoints, boolean fast, HttpUrl fixtureEndpoint) {
        if (apiKey == null || !apiKey.matches("[!-~]{1,512}")) throw new IllegalArgumentException("IPQualityScore requires a valid API key (value redacted).");
        if (strictness < 0 || strictness > 3) throw new IllegalArgumentException("IPQualityScore strictness must be 0..3.");
        if (fixtureEndpoint != null && (!fixtureEndpoint.scheme().equals("http") || !fixtureEndpoint.host().equals("127.0.0.1")
                || !fixtureEndpoint.username().isEmpty() || !fixtureEndpoint.password().isEmpty()
                || fixtureEndpoint.query() != null || fixtureEndpoint.fragment() != null
                || !fixtureEndpoint.encodedPath().equals("/api/json/ip"))) throw new IllegalArgumentException("Invalid loopback fixture endpoint.");
        this.apiKey = apiKey; this.strictness = strictness; this.allowPublicAccessPoints = allowPublicAccessPoints;
        this.fast = fast; this.fixtureEndpoint = fixtureEndpoint;
    }
    Request request(String address) {
        String ip = Exemptions.normalize(address);
        HttpUrl url = (fixtureEndpoint == null ? ENDPOINT : fixtureEndpoint).newBuilder()
                .addQueryParameter("ip", ip).addQueryParameter("strictness", Integer.toString(strictness))
                .addQueryParameter("allow_public_access_points", Boolean.toString(allowPublicAccessPoints))
                .addQueryParameter("fast", Boolean.toString(fast)).build();
        return new Request.Builder().url(url).header("IPQS-KEY", apiKey).build();
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String address) {
        return ProviderHttp.submit(() -> {
            try {
                String ip = Exemptions.normalize(address);
                Optional<JsonObject> json = ProviderHttp.readJson(request(ip), "IpQualityScoreVpnProvider");
                return json.isPresent() ? parse(ip, json.get()) : Optional.empty();
            } catch (RuntimeException failure) { throw ProviderHttp.failure(failure); }
        });
    }
    static Optional<VpnResult> parse(String ip, JsonObject json) {
        if (!ProviderHttp.bool(json, "success")) {
            String message = DetectionFields.text(json, "message");
            String prefix = "You have insufficient credits";
            if (message != null && (message.equals(prefix) || message.startsWith(prefix + " ")))
                throw new LookupException(FailureReason.RATE_LIMIT, 3600000);
            throw new LookupException(FailureReason.INVALID_RESPONSE);
        }
        DetectionFields.address(json, "ip", ip);
        boolean vpn = ProviderHttp.bool(json, "vpn"), proxy = ProviderHttp.bool(json, "proxy"), tor = ProviderHttp.bool(json, "tor");
        if (!proxy && (vpn || tor)) throw new IllegalArgumentException("Inconsistent IPQS classifications.");
        if (json.has("ASN") && !json.get("ASN").isJsonNull()
                && (!json.get("ASN").isJsonPrimitive() || !json.get("ASN").getAsJsonPrimitive().isNumber()))
            throw new IllegalArgumentException("Invalid IPQS ASN.");
        VpnResult result = new VpnResult(ip, vpn || proxy || tor);
        result.setDetails(DetectionDetails.withExactRisk(DetectionFields.types(json, DetectionDetails.Type.VPN, DetectionDetails.Type.PROXY,
                DetectionDetails.Type.TOR), DetectionFields.asn(json, "ASN", false), knownText(json, "ISP"), null,
                knownText(json, "country_code"), DetectionFields.decimalScore(json, "fraud_score"), null));
        return Optional.of(result);
    }
    private static String knownText(JsonObject json, String key) {
        String text = DetectionFields.text(json, key);
        return text != null && text.equals("N/A") ? null : text;
    }
}
