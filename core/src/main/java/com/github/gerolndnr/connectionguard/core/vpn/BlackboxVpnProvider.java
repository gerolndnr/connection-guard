package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.*;
import okhttp3.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Aggregate listing, deliberately broader than an explicit VPN classification. */
public final class BlackboxVpnProvider implements VpnProvider {
    public static final String LISTED_REASON = "Listed by Blackbox (VPN/proxy/Tor/hosting/cloud)";
    private static final HttpUrl ENDPOINT = HttpUrl.get("https://blackbox.ipinfo.app/api/v1");
    private final transient HttpUrl fixtureEndpoint;
    public BlackboxVpnProvider() { this(null); }
    BlackboxVpnProvider(HttpUrl fixtureEndpoint) { this.fixtureEndpoint = KeylessEndpoint.fixture(fixtureEndpoint); }
    @Override public String sourceName() { return "blackbox"; }
    @Override public boolean stableSourceId() { return true; }
    Request request(String ip) {
        return new Request.Builder().url((fixtureEndpoint == null ? ENDPOINT : fixtureEndpoint).newBuilder()
                .addPathSegment(ProviderAddresses.compressed(ip)).build()).build();
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return ProviderHttp.submit(() -> parse(ip, ProviderHttp.readText(request(ip), sourceName())));
    }
    static Optional<VpnResult> parse(String ip, String body) {
        String flag = KeylessEndpoint.text(body);
        if (!flag.equals("Y") && !flag.equals("N")) return Optional.empty();
        // Never fabricate specific VPN/HOSTING flags from an aggregate membership answer.
        return Optional.of(new VpnResult(ip, flag.equals("Y"), flag.equals("Y") ? Optional.of(LISTED_REASON) : Optional.empty()));
    }
}
