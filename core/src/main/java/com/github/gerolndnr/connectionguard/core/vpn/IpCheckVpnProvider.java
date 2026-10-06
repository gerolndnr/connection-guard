package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.*;
import okhttp3.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Keyless ip-check.net; no undocumented text is a negative verdict. */
public final class IpCheckVpnProvider implements VpnProvider {
    private static final HttpUrl ENDPOINT = HttpUrl.get("https://ip-check.net/api/proxy-detect.php");
    private final transient HttpUrl fixtureEndpoint;
    public IpCheckVpnProvider() { this(null); }
    IpCheckVpnProvider(HttpUrl fixtureEndpoint) { this.fixtureEndpoint = KeylessEndpoint.fixture(fixtureEndpoint); }
    @Override public String sourceName() { return "ipcheck"; }
    @Override public boolean stableSourceId() { return true; }
    Request request(String ip) {
        return new Request.Builder().url((fixtureEndpoint == null ? ENDPOINT : fixtureEndpoint).newBuilder()
                .addQueryParameter("ip", ProviderAddresses.compressed(ip)).build()).build();
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return ProviderHttp.submit(() -> parse(ip, ProviderHttp.readText(request(ip), sourceName())));
    }
    static Optional<VpnResult> parse(String ip, String body) {
        String flag = KeylessEndpoint.text(body);
        if (!flag.equals("TRUE") && !flag.equals("FALSE")) return Optional.empty();
        return Optional.of(new VpnResult(ip, flag.equals("TRUE")));
    }
}
