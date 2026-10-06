package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider;
import com.google.gson.JsonObject;
import okhttp3.HttpUrl;
import okhttp3.Request;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** One bounded v2 vpn=1 answer for the VPN and geo scopes of a login. */
public final class ProxyCheckClient {
    private final String key;
    private final HttpUrl endpoint;
    private final Map<String, Flight> flights = new LinkedHashMap<>();
    private static final class Flight {
        final CompletableFuture<Optional<JsonObject>> result = new CompletableFuture<>();
        final AtomicBoolean transmitted = new AtomicBoolean();
        final long created = System.nanoTime();
        boolean vpn, geo;
        boolean claimed(boolean geoScope) { return geoScope ? geo : vpn; }
        void claim(boolean geoScope) { if (geoScope) geo = true; else vpn = true; }
    }
    public ProxyCheckClient(String key) { this(key, HttpUrl.get("https://proxycheck.io/v2/")); }
    // Package-private transport seam: production always uses the official HTTPS endpoint.
    ProxyCheckClient(String key, HttpUrl endpoint) { this.key = key; this.endpoint = endpoint; }

    public CompletableFuture<Optional<JsonObject>> fetch(String ip) {
        return ProviderHttp.submit(() -> {
            try {
                HttpUrl url = endpoint.newBuilder().addPathSegment(ProviderAddresses.compressed(ip))
                        .addQueryParameter("key", key).addQueryParameter("vpn", "1")
                        .addQueryParameter("asn", "1").addQueryParameter("risk", "1").addQueryParameter("tag", "0").build();
                Optional<JsonObject> answer = ProviderHttp.readJson(new Request.Builder().url(url).build(), "ProxyCheck");
                // Validate the shared verdict before recording a successful provider call.
                if (!answer.isPresent() || !ProxyCheckVpnProvider.parse(ip, answer.get()).isPresent())
                    throw new LookupException(FailureReason.INVALID_RESPONSE);
                return answer;
            } catch (RuntimeException error) { throw ProviderHttp.failure(error); }
        });
    }

    public CompletableFuture<Optional<JsonObject>> query(String ip, boolean geoScope, int capacity, long retentionMillis,
            Function<Runnable, CompletableFuture<Optional<JsonObject>>> operation, Runnable attempted) {
        Flight selected; boolean owner = false;
        synchronized (flights) {
            long now = System.nanoTime();
            flights.entrySet().removeIf(e -> e.getValue().result.isDone()
                    && (e.getValue().vpn && e.getValue().geo || now - e.getValue().created >= TimeUnit.MILLISECONDS.toNanos(retentionMillis)));
            selected = flights.get(ip);
            // Concurrent callers always share. A completed answer can be consumed only by the other scope,
            // so disabling the persistent cache still causes the next VPN-only lookup to make a fresh query.
            if (selected == null || selected.result.isDone() && selected.claimed(geoScope)) {
                if (selected != null) flights.remove(ip);
                if (flights.size() >= capacity) {
                    Iterator<Flight> entries = flights.values().iterator();
                    while (entries.hasNext()) { if (entries.next().result.isDone()) { entries.remove(); break; } }
                    if (flights.size() >= capacity) return failed(FailureReason.OVERLOADED);
                }
                selected = new Flight(); flights.put(ip, selected); owner = true;
            }
            selected.claim(geoScope);
        }
        final Flight flight = selected;
        // Never call the runtime/core while holding the flight monitor.
        if (owner) {
            try {
                operation.apply(() -> flight.transmitted.set(true)).whenComplete((answer, error) -> {
                    if (error == null) flight.result.complete(answer); else flight.result.completeExceptionally(error);
                });
            } catch (RuntimeException error) { flight.result.completeExceptionally(error); }
        }
        CompletableFuture<Optional<JsonObject>> view = new CompletableFuture<>();
        flight.result.whenComplete((answer, error) -> {
            if (flight.transmitted.get()) attempted.run();
            if (error == null) view.complete(answer.map(JsonObject::deepCopy)); else view.completeExceptionally(error);
        });
        return view;
    }
    private static <T> CompletableFuture<T> failed(FailureReason reason) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(new LookupException(reason)); return result;
    }
    public void clear() { synchronized (flights) { flights.entrySet().removeIf(e -> e.getValue().result.isDone()); } }
}
