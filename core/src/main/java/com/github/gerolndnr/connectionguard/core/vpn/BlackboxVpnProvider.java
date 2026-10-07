package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.*;
import com.github.gerolndnr.connectionguard.core.local.*;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;
import okhttp3.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Aggregate listing, deliberately broader than an explicit VPN classification. */
public final class BlackboxVpnProvider implements VpnProvider {
    public static final String LISTED_REASON = "Listed by Blackbox (VPN/proxy/Tor/hosting/cloud)";
    public static final String UNCONFIRMED_REASON = "Blackbox listed the address, not confirmed";
    private static final HttpUrl ENDPOINT = HttpUrl.get("https://blackbox.ipinfo.app/api/v1");
    private final transient HttpUrl fixtureEndpoint;
    private final boolean requireConfirmation;
    private final transient IntelSnapshot intel;
    // Included in provider fingerprints; confirmation changes invalidate old cached positives.
    private final String intelGeneration;
    public BlackboxVpnProvider() { this(true,null,null); }
    public BlackboxVpnProvider(boolean requireConfirmation,IntelSnapshot intel) { this(requireConfirmation,intel,null); }
    BlackboxVpnProvider(HttpUrl fixtureEndpoint) { this(true,null,fixtureEndpoint); }
    BlackboxVpnProvider(boolean requireConfirmation,IntelSnapshot intel,HttpUrl fixtureEndpoint) {
        this.fixtureEndpoint=KeylessEndpoint.fixture(fixtureEndpoint);this.requireConfirmation=requireConfirmation;this.intel=intel;
        this.intelGeneration=intel==null?"missing":intel.generation;
    }
    @Override public String sourceName() { return "blackbox"; }
    @Override public boolean stableSourceId() { return true; }
    Request request(String ip) {
        return new Request.Builder().url((fixtureEndpoint == null ? ENDPOINT : fixtureEndpoint).newBuilder()
                .addPathSegment(ProviderAddresses.compressed(ip)).build()).build();
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return ProviderHttp.submit(() -> parse(ip, ProviderHttp.readText(request(ip), sourceName())).map(result -> {
            if(result.isVpn()&&requireConfirmation){
                long now=System.currentTimeMillis();
                if(intel==null||!intel.contains(LocalSource.Kind.HOSTING,ip,now)){
                    result=new VpnResult(ip,false,Optional.of(UNCONFIRMED_REASON));result.setUnknown(FailureReason.NO_EVIDENCE);
                }else {
                    result.setValidUntil(intel.validUntil());result.setSourceVersion(intel.generation);
                }
            }
            return result;
        }));
    }
    static Optional<VpnResult> parse(String ip, String body) {
        String flag = KeylessEndpoint.text(body);
        if (!flag.equals("Y") && !flag.equals("N")) return Optional.empty();
        // Never fabricate specific VPN/HOSTING flags from an aggregate membership answer.
        return Optional.of(new VpnResult(ip, flag.equals("Y"), flag.equals("Y") ? Optional.of(LISTED_REASON) : Optional.empty()));
    }
}
