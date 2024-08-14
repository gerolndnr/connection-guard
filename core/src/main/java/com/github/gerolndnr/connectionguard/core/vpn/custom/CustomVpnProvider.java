package com.github.gerolndnr.connectionguard.core.vpn.custom;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
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
            OkHttpClient httpClient = new OkHttpClient();

            // Set URL
            Request.Builder requestBuilder = new Request.Builder()
                    .url(requestUrl.replaceAll("%IP%", ipAddress));

            // Set method and request body
            switch (requestType.toUpperCase()) {
                case "GET":
                    break;
                case "POST":
                    requestBuilder = requestBuilder.post(
                            RequestBody.create(requestBody.replaceAll("%IP%", ipAddress), MediaType.get(requestBodyType))
                    );
                    break;
                default:
                    ConnectionGuard.getLogger().info("Custom Detection Provider | Unknown request type. Please use 'GET' or 'POST'!");
                    return Optional.empty();
            }

            // Set request headers
            for (String header : requestHeaders) {
                String[] headerSplit = header.split(":");
                requestBuilder = requestBuilder.addHeader(headerSplit[0], headerSplit[1]);
            }

            Response response;
            try {
                response = httpClient.newCall(requestBuilder.build()).execute();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info(
                        "Could not execute GET request on custom vpn detection provider."
                );
                return Optional.empty();
            }

            switch (responseType.toLowerCase()) {
                case "application/json":
                    try {
                        return readJsonResponse(ipAddress, response.body().string());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                default:
                    ConnectionGuard.getLogger().info("Custom Detection Provider | Unknown response type. Please use 'application/json'!");
                    break;
            }

            return Optional.empty();
        });
    }

    private Optional<VpnResult> readJsonResponse(String ipAddress, String responseBody) {
        JsonElement jsonElement = JsonParser.parseString(responseBody);
        String[] isVpnTree = isVpnFieldName.replaceAll("%IP%", ipAddress).split("#");

        if (isVpnTree.length == 0)
            isVpnTree = new String[]{ isVpnFieldName.replaceAll("%IP%", ipAddress) };
        JsonObject isVpnObject = jsonElement.getAsJsonObject();
        boolean isVpn = false;
        ConnectionGuard.getLogger().info(String.valueOf(isVpnTree.length));

        for (int i = 0; i < isVpnTree.length; i++) {
            if (isVpnTree.length - 1 == i) {
                if (isVpnFieldType.equalsIgnoreCase("STRING")) {
                    String isVpnResult = isVpnObject.get(isVpnTree[i]).getAsString();
                    if (isVpnResult.equalsIgnoreCase(isVpnString)) {
                        isVpn = true;
                        break;
                    }
                }
                if (isVpnFieldType.equalsIgnoreCase("BOOLEAN")) {
                    isVpn = isVpnObject.get(isVpnTree[i]).getAsBoolean();
                    break;
                }
            } else {
                isVpnObject = isVpnObject.getAsJsonObject(isVpnTree[i]);
            }
        }

        if (vpnProviderFieldName.equalsIgnoreCase("")) {
            return Optional.of(new VpnResult(ipAddress, isVpn));
        }

        String[] vpnProviderNameTree = vpnProviderFieldName.replaceAll("%IP%", ipAddress).split(".");
        if (vpnProviderNameTree.length == 0)
            vpnProviderNameTree = new String[]{ vpnProviderFieldName.replaceAll("%IP%", ipAddress) };
        JsonObject vpnProviderNameObject = jsonElement.getAsJsonObject();
        String vpnProviderName = "";

        for (int i = 0; i < vpnProviderNameTree.length; i++) {
            if (vpnProviderNameTree.length - 1 == i) {
                vpnProviderName = vpnProviderNameObject.get(vpnProviderNameTree[i]).getAsString();
                break;
            } else {
                vpnProviderNameObject = vpnProviderNameObject.get(vpnProviderNameTree[i]).getAsJsonObject();
            }
        }

        return Optional.of(new VpnResult(ipAddress, isVpn, vpnProviderName));
    }
}
