package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObserver;
import com.github.gerolndnr.connectionguard.api.v1.DetectionMetadata;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Built-in decision observer for the cloud. Always aggregates anonymous counters; keeps
 * individual decisions only after the server confirmed the install is linked (acceptEvents).
 * Runs on the observer workers, never on a login thread.
 */
final class CloudRecorder implements DecisionObserver {
    static final int MAX_BUFFERED_EVENTS = 5000;
    private static final int MAX_LATENCY_SAMPLES = 2048;

    private final Object lock = new Object();
    private volatile boolean acceptEvents;
    private long windowStart = System.currentTimeMillis();
    private long checks, allowed, denied, errors, vpnPositive, geoFlagged, cacheHits, lookups, droppedEvents;
    private final Map<String, Long> countries = new TreeMap<>();
    private final Map<String, Long> reasons = new TreeMap<>();
    private final List<Long> latency = new ArrayList<>();
    private final Random sampler = new Random();
    private long latencySeen;
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();

    void acceptEvents(boolean accept) {
        acceptEvents = accept;
        if (!accept) synchronized (lock) { events.clear(); }
    }

    @Override public void onDecision(DecisionObservation o) {
        JsonObject event = acceptEvents ? event(o) : null;
        synchronized (lock) {
            checks++;
            switch (o.getOutcome()) { case DENY: denied++; break; case ERROR: errors++; break; default: allowed++; }
            if (o.getVpnCheck() == DecisionObservation.Check.POSITIVE) vpnPositive++;
            if (o.getFlags().contains(DecisionObservation.Flag.GEO)) geoFlagged++;
            boolean vpnLooked = false, cached = false;
            String country = null;
            for (DecisionObservation.Source s : o.getSources()) {
                if (s.getScope() == DecisionObservation.Scope.VPN) { vpnLooked = true; cached |= s.isFromCache(); }
                DetectionMetadata m = s.getObservation().getMetadata();
                if (country == null && m != null && m.getCountry() != null && m.getCountry().matches("[A-Z]{2}")) country = m.getCountry();
            }
            if (vpnLooked) { if (cached) cacheHits++; else lookups++; }
            if (country != null && (countries.containsKey(country) || countries.size() < 64)) countries.merge(country, 1L, Long::sum);
            reasons.merge(o.getReason().name(), 1L, Long::sum);
            // Reservoir sample keeps percentiles meaningful under heavy load with bounded memory.
            latencySeen++;
            if (latency.size() < MAX_LATENCY_SAMPLES) latency.add(o.getDurationMillis());
            else { long slot = (long) (sampler.nextDouble() * latencySeen); if (slot < MAX_LATENCY_SAMPLES) latency.set((int) slot, o.getDurationMillis()); }
            if (event != null) {
                if (events.size() >= MAX_BUFFERED_EVENTS) { events.pollFirst(); droppedEvents++; }
                events.addLast(event);
            }
        }
    }

    /** A sync body's counters, events and buffer state. The caller commits or restores it. */
    static final class Batch {
        final JsonObject counters;
        final List<JsonObject> events;
        final int buffered;
        final long dropped;
        Batch(JsonObject counters, List<JsonObject> events, int buffered, long dropped) {
            this.counters = counters; this.events = events; this.buffered = buffered; this.dropped = dropped;
        }
    }

    /** Takes the current window and up to maxEvents oldest events. */
    Batch drain(int maxEvents, long now) {
        synchronized (lock) {
            JsonObject c = new JsonObject();
            c.addProperty("window_start", windowStart);
            c.addProperty("window_end", now);
            c.addProperty("checks", checks); c.addProperty("allowed", allowed); c.addProperty("denied", denied);
            c.addProperty("errors", errors); c.addProperty("vpn_positive", vpnPositive); c.addProperty("geo_flagged", geoFlagged);
            c.addProperty("cache_hits", cacheHits); c.addProperty("lookups", lookups);
            List<Long> sorted = new ArrayList<>(latency); Collections.sort(sorted);
            if (sorted.isEmpty()) { c.add("latency_ms_p50", null); c.add("latency_ms_p95", null); }
            else {
                c.addProperty("latency_ms_p50", Math.min(600_000, sorted.get(sorted.size() / 2)));
                c.addProperty("latency_ms_p95", Math.min(600_000, sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(sorted.size() * 0.95)))));
            }
            c.add("countries", counts(countries));
            c.add("reasons", counts(reasons));
            List<JsonObject> out = new ArrayList<>();
            Iterator<JsonObject> it = events.iterator();
            while (it.hasNext() && out.size() < maxEvents) out.add(it.next());
            Batch batch = new Batch(c, out, events.size() - out.size(), droppedEvents);
            windowStart = now;
            checks = allowed = denied = errors = vpnPositive = geoFlagged = cacheHits = lookups = 0;
            countries.clear(); reasons.clear(); latency.clear(); latencySeen = 0;
            for (int i = 0; i < out.size(); i++) events.pollFirst();
            return batch;
        }
    }

    /** A failed sync puts its counters and events back so nothing is lost or double counted. */
    void restore(Batch batch) {
        synchronized (lock) {
            JsonObject c = batch.counters;
            windowStart = Math.min(windowStart, c.get("window_start").getAsLong());
            checks += c.get("checks").getAsLong(); allowed += c.get("allowed").getAsLong(); denied += c.get("denied").getAsLong();
            errors += c.get("errors").getAsLong(); vpnPositive += c.get("vpn_positive").getAsLong(); geoFlagged += c.get("geo_flagged").getAsLong();
            cacheHits += c.get("cache_hits").getAsLong(); lookups += c.get("lookups").getAsLong();
            c.getAsJsonObject("countries").entrySet().forEach(e -> countries.merge(e.getKey(), e.getValue().getAsLong(), Long::sum));
            c.getAsJsonObject("reasons").entrySet().forEach(e -> reasons.merge(e.getKey(), e.getValue().getAsLong(), Long::sum));
            if (acceptEvents) {
                List<JsonObject> back = new ArrayList<>(batch.events);
                Collections.reverse(back);
                for (JsonObject e : back) {
                    if (events.size() >= MAX_BUFFERED_EVENTS) { droppedEvents++; continue; }
                    events.addFirst(e);
                }
            }
        }
    }

    int buffered() { synchronized (lock) { return events.size(); } }
    long dropped() { synchronized (lock) { return droppedEvents; } }

    private static JsonObject counts(Map<String, Long> map) {
        JsonObject o = new JsonObject();
        map.forEach(o::addProperty);
        return o;
    }

    static String sourceId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("^[^a-z]+", "");
        if (id.isEmpty()) id = "source";
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    private static String clip(String value, int max) { return value == null ? null : value.length() > max ? value.substring(0, max) : value; }

    static JsonObject event(DecisionObservation o) {
        JsonObject e = new JsonObject();
        e.addProperty("id", UUID.randomUUID().toString());
        e.addProperty("at", o.getObservedAt());
        e.addProperty("platform", o.getPlatform().name());
        e.addProperty("phase", o.getPhase().name());
        e.addProperty("mode", o.getMode().name());
        e.addProperty("outcome", o.getOutcome().name());
        e.addProperty("reason", o.getReason().name());
        e.addProperty("identity_trust", o.getIdentityTrust().name());
        e.addProperty("uuid", o.getTrustedUuid().map(UUID::toString).orElse(null));
        e.addProperty("ip", o.getIp());
        e.addProperty("vpn", o.getVpnCheck().name());
        e.addProperty("geo", o.getGeoCheck().name());
        JsonArray flags = new JsonArray();
        for (DecisionObservation.Flag f : o.getFlags()) flags.add(f.name());
        e.add("flags", flags);
        e.addProperty("duration_ms", Math.min(600_000, Math.max(0, o.getDurationMillis())));
        JsonArray sources = new JsonArray();
        for (DecisionObservation.Source s : o.getSources()) {
            if (sources.size() >= 16) break;
            DetectionMetadata m = s.getObservation().getMetadata();
            JsonObject j = new JsonObject();
            j.addProperty("id", sourceId(s.getId()));
            j.addProperty("scope", s.getScope().name());
            j.addProperty("status", s.getObservation().getStatus().name());
            j.addProperty("reason", s.getObservation().getReason().name());
            j.addProperty("duration_ms", Math.min(600_000, Math.max(0, s.getDurationMillis())));
            j.addProperty("voting", s.isVoting());
            j.addProperty("from_cache", s.isFromCache());
            String country = m == null ? null : m.getCountry();
            j.addProperty("country", country != null && country.matches("[A-Z]{2}") ? country : null);
            Long asn = m == null ? null : m.getAsn();
            j.addProperty("asn", asn != null && asn >= 0 && asn <= 4_294_967_295L ? asn : null);
            j.addProperty("isp", m == null ? null : clip(m.getIsp(), 128));
            Integer risk = m == null ? null : m.getRisk();
            j.addProperty("risk", risk == null ? null : Math.max(0, Math.min(100, risk)));
            sources.add(j);
        }
        e.add("sources", sources);
        JsonArray rules = new JsonArray();
        for (DecisionObservation.Rule r : o.getRules()) {
            if (rules.size() >= 16) break;
            JsonObject j = new JsonObject();
            j.addProperty("id", clip(r.getId(), 64));
            j.addProperty("scope", r.getEvaluatedScope().name());
            j.addProperty("effect", r.getEffect().name());
            j.addProperty("match", r.getMatch().name());
            j.addProperty("selected", r.isSelected());
            rules.add(j);
        }
        e.add("rules", rules);
        return e;
    }
}
