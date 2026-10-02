package com.github.gerolndnr.connectionguard.core.vpn.custom;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.*;

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
        return ProviderHttp.submit(() -> {
            try {
                Request.Builder request = new Request.Builder().url(requestUrl.replace("%IP%", ipAddress));
                if (requestType.equalsIgnoreCase("POST")) {
                    request.post(RequestBody.create(requestBody.replace("%IP%", ipAddress), MediaType.get(requestBodyType)));
                } else if (!requestType.equalsIgnoreCase("GET")) {
                    throw new IllegalArgumentException("Unsupported request type");
                }
                for (String header : requestHeaders) {
                    String[] parts = header.split(":", 2);
                    if (parts.length != 2) throw new IllegalArgumentException("Invalid request header");
                    request.addHeader(parts[0].trim(), parts[1].trim().replace("%IP%", ipAddress));
                }
                if (!responseType.equalsIgnoreCase("application/json")) {
                    throw new IllegalArgumentException("Unsupported response type");
                }
                Optional<JsonObject> json = ProviderHttp.readJson(request.build(), "Custom VPN provider");
                return json.isPresent() ? readJsonResponse(ipAddress, json.get()) : Optional.empty();
            } catch (RuntimeException failure) {
                throw ProviderHttp.failure(failure);
            }
        });
    }

    private Optional<VpnResult> readJsonResponse(String ipAddress, JsonObject json) {
        JsonElement flag = field(json, isVpnFieldName.replace("%IP%", ipAddress));
        boolean isVpn;
        if (isVpnFieldType.equalsIgnoreCase("STRING") && flag.isJsonPrimitive()
                && flag.getAsJsonPrimitive().isString()) {
            isVpn = flag.getAsString().equalsIgnoreCase(isVpnString);
        } else if (isVpnFieldType.equalsIgnoreCase("BOOLEAN") && flag.isJsonPrimitive()
                && flag.getAsJsonPrimitive().isBoolean()) {
            isVpn = flag.getAsBoolean();
        } else {
            throw new IllegalArgumentException("Invalid VPN flag type");
        }
        if (vpnProviderFieldName.isEmpty()) return Optional.of(new VpnResult(ipAddress, isVpn));
        JsonElement name = field(json, vpnProviderFieldName.replace("%IP%", ipAddress));
        if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid VPN provider name");
        }
        return Optional.of(new VpnResult(ipAddress, isVpn, Optional.of(name.getAsString())));
    }

    private static JsonElement field(JsonObject json, String path) {
        JsonElement value = json;
        // Both fields use the documented '#' separator; IPv4 keys contain literal dots.
        for (String key : path.split("#", -1)) {
            if (key.isEmpty() || !value.isJsonObject()) throw new IllegalArgumentException("Invalid field path");
            value = value.getAsJsonObject().get(key);
            if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing field");
        }
        return value;
    }
}
