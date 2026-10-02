package com.github.gerolndnr.connectionguard.core.vpn.custom;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.github.gerolndnr.connectionguard.core.http.DetectionFields;
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
    private Map<String, String> detailsFields;

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
        this(requestType, requestUrl, requestHeaders, requestBodyType, requestBody, responseType, isVpnFieldName, isVpnFieldType, isVpnString, vpnProviderFieldName, Collections.emptyMap());
    }
    public CustomVpnProvider(String requestType, String requestUrl, List<String> requestHeaders, String requestBodyType,
                             String requestBody, String responseType, String isVpnFieldName, String isVpnFieldType,
                             String isVpnString, String vpnProviderFieldName, Map<String, String> detailsFields) {
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
        this.detailsFields = Collections.unmodifiableMap(new java.util.HashMap<>(detailsFields));
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
        Optional<String> providerName = Optional.empty();
        if (!vpnProviderFieldName.isEmpty()) {
            JsonObject name = new JsonObject(); name.add("name", field(json, vpnProviderFieldName.replace("%IP%", ipAddress)));
            providerName = Optional.ofNullable(DetectionFields.text(name, "name"));
        }
        VpnResult result = new VpnResult(ipAddress, isVpn, providerName);
        JsonObject details = new JsonObject();
        for (Map.Entry<String, String> entry : detailsFields.entrySet()) {
            // A configured but absent optional metadata field stays unknown, not false/zero.
            JsonElement value = optionalField(json, entry.getValue().replace("%IP%", ipAddress));
            if (value != null) details.add(entry.getKey(), value);
        }
        result.setDetails(new DetectionDetails(DetectionFields.types(details, DetectionDetails.Type.values()),
                DetectionFields.asn(details, "asn", false), DetectionFields.text(details, "isp"), DetectionFields.text(details, "operator"),
                DetectionFields.text(details, "country"), DetectionFields.score(details, "risk"), DetectionFields.score(details, "confidence")));
        return Optional.of(result);
    }

    private static JsonElement optionalField(JsonObject json, String path) {
        JsonElement value = json;
        for (String key : path.split("#", -1)) {
            if (value == null || value.isJsonNull()) return null;
            if (key.isEmpty() || !value.isJsonObject()) throw new IllegalArgumentException("Invalid metadata path.");
            value = value.getAsJsonObject().get(key);
        }
        return value == null || value.isJsonNull() ? null : value;
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
