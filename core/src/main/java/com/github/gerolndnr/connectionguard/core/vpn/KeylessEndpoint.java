package com.github.gerolndnr.connectionguard.core.vpn;

import okhttp3.HttpUrl;

/** Production endpoints are fixed HTTPS. Only package-local tests can select owned loopback. */
final class KeylessEndpoint {
    private KeylessEndpoint() { }
    static String text(String body) { return body.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", ""); }
    static HttpUrl fixture(HttpUrl url) {
        if (url != null && (!url.scheme().equals("http") || !url.host().equals("127.0.0.1")
                || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null))
            throw new IllegalArgumentException("Invalid loopback fixture endpoint.");
        return url;
    }
}
