package com.github.gerolndnr.connectionguard.core.vpn.custom;

import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class CustomVpnProvider implements VpnProvider {
    private String requestType;
    private String requestUrl;
    private List<String> requestHeaders;
    private String requestBodyType;
    private String requestBody;
    private String responseType;
    private String isVpnFieldName;
    private String isVpnFieldType;
    private String isVpnString;
    private String vpnProviderFieldName;

    public CustomVpnProvider(
            String requestType,
            String requestUrl,
            List<String> requestHeaders,
            String requestBodyType,
            String requestBody,
            String responseType,
            String isVpnFieldName,
            String isVpnFieldType,
            String isVpnString,
            String vpnProviderFieldName
    ) {
        this.requestType = requestType;
        this.requestUrl = requestUrl;
        this.requestHeaders = requestHeaders;
        this.requestBodyType = requestBodyType;
        this.requestBody = requestBody;
        this.responseType = responseType;
        this.isVpnFieldName = isVpnFieldName;
        this.isVpnFieldType = isVpnFieldType;
        this.isVpnString = isVpnString;
        this.vpnProviderFieldName = vpnProviderFieldName;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            return Optional.empty();
        });
    }
}
