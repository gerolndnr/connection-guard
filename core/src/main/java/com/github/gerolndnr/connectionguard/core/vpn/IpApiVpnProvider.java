package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.google.gson.JsonObject;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import okhttp3.Request;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpApiVpnProvider implements VpnProvider {


    public IpApiVpnProvider() {  }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return ProviderHttp.submit(() -> {
            try {
                Optional<JsonObject> json = ProviderHttp.readJson(new Request.Builder().url("http://ip-api.com/json/" + ipAddress + "?fields=status,message,proxy,hosting,as,isp,countryCode").build(), "IpApiVpnProvider");
                return json.isPresent() ? parse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    static Optional<VpnResult> parse(String ipAddress, JsonObject json) {
        if (!ProviderHttp.string(json, "status").equalsIgnoreCase("success")) return Optional.empty();
        DetectionFields.address(json, "query", ipAddress);
        VpnResult result = new VpnResult(ipAddress, ProviderHttp.bool(json, "proxy"));
        // IP-API's proxy flag includes VPN and Tor; it is not a separate PROXY classification.
        result.setDetails(new DetectionDetails(DetectionFields.types(json, DetectionDetails.Type.HOSTING),
                DetectionFields.asn(json, "as", true), DetectionFields.text(json, "isp"), null,
                DetectionFields.text(json, "countryCode"), null, null));
        return Optional.of(result);
    }
}
