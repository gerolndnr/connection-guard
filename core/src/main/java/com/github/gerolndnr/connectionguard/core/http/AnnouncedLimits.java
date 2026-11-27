package com.github.gerolndnr.connectionguard.core.http;

import okhttp3.Response;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-minute request limits that services announce themselves in {@code X-RateLimit-Limit}.
 * Only read for sources configured with {@code minute-budget: auto}; never a claim about an account.
 */
public final class AnnouncedLimits {
    /** Upper bound for an announced limit; a larger header is treated as this value. */
    public static final int MAX = 1000;
    private static final Map<String, Integer> LIMITS = new ConcurrentHashMap<>();

    private AnnouncedLimits() { }

    static void observe(String source, Response response) {
        if (source == null) return;
        String header = response.header("X-RateLimit-Limit");
        if (header == null) return;
        try {
            int limit = Integer.parseInt(header.trim());
            if (limit > 0) LIMITS.put(source, Math.min(MAX, limit));
        } catch (NumberFormatException ignored) { /* Keep the last valid announcement. */ }
    }

    /** The last limit the source announced, or 0 if it never sent a valid one. */
    public static int get(String source) { return source == null ? 0 : LIMITS.getOrDefault(source, 0); }

    /** For tests and plugin shutdown. */
    public static void clear() { LIMITS.clear(); }

    public static void set(String source, int limit) { LIMITS.put(source, Math.min(MAX, limit)); }
}
