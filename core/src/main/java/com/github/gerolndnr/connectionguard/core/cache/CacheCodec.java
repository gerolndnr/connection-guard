package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.google.gson.Gson;
import java.util.Optional;

/** Plain data only: no reflective access to java.util.Optional or platform internals. */
public final class CacheCodec {
    private static final Gson GSON = new Gson();
    private CacheCodec() { }
    public static String encode(Object result) { return GSON.toJson(result); }
    public static Optional<VpnResult> vpn(String json, String ip, long ttlMillis) {
        try {
            if (json == null || json.length() > 262144) return Optional.empty();
            com.google.gson.JsonObject raw = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!raw.has("positiveThreshold") || !raw.get("positiveThreshold").isJsonPrimitive()
                    || !raw.get("positiveThreshold").getAsJsonPrimitive().isNumber()
                    || !raw.get("positiveThreshold").getAsString().matches("[1-9]|1[0-6]")) return Optional.empty();
            validateProvenance(raw);
            validateDetails(raw.get("details"));
            if (raw.has("votes")) for (com.google.gson.JsonElement vote : raw.getAsJsonArray("votes")) {
                com.google.gson.JsonObject source = vote.getAsJsonObject();
                if (!source.has("voting") || !source.get("voting").isJsonPrimitive() || !source.get("voting").getAsJsonPrimitive().isBoolean()) return Optional.empty();
                validateProvenance(source); validateDetails(source.get("details"));
            }
            VpnResult result = GSON.fromJson(json, VpnResult.class);
            if (result == null || !ip.equals(result.getIpAddress()) || result.getStatus() == null
                    || result.getStatus() == ProviderVote.Status.UNKNOWN
                    || result.isVpn() != (result.getStatus() == ProviderVote.Status.POSITIVE)
                    || result.getPositiveThreshold() < 1 || result.getPositiveThreshold() > 16 || !fresh(result.getCachedOn(), ttlMillis)) return Optional.empty();
            if (result.getVotes().size() > 16) return Optional.empty();
            result.getDetails().validate();
            if (result.getValidUntil() < 0 || result.getValidUntil() != 0 && System.currentTimeMillis() >= result.getValidUntil()) return Optional.empty();
            result.setSourceVersion(result.getSourceVersion());
            for (ProviderVote vote : result.getVotes()) { if (vote == null || !vote.isFresh(System.currentTimeMillis())) return Optional.empty(); vote.validate(); }
            return Optional.of(result);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    public static Optional<GeoResult> geo(String json, String ip, long ttlMillis) {
        try {
            if (json == null || json.length() > 262144) return Optional.empty();
            validateProvenance(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
            GeoResult result = GSON.fromJson(json, GeoResult.class);
            if (result == null || !ip.equals(result.getIpAddress()) || result.getCountryName() == null
                    || !fresh(result.getCachedOn(), ttlMillis) || result.getValidUntil() != 0 && System.currentTimeMillis() >= result.getValidUntil()) return Optional.empty();
            result.validate();
            return Optional.of(result);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    private static void validateProvenance(com.google.gson.JsonObject object) {
        if (object.has("validUntil")) {
            com.google.gson.JsonElement until = object.get("validUntil");
            if (!until.isJsonPrimitive() || !until.getAsJsonPrimitive().isNumber() || !until.getAsString().matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid source expiry.");
            Long.parseLong(until.getAsString());
        }
        if (object.has("sourceVersion") && !object.get("sourceVersion").isJsonNull()) {
            com.google.gson.JsonElement version = object.get("sourceVersion");
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString() || !version.getAsString().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source version.");
        }
    }
    private static boolean fresh(long cached, long ttl) {
        long now = System.currentTimeMillis();
        return cached > 0 && cached <= now && now - cached < ttl;
    }
    private static void validateDetails(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        com.google.gson.JsonObject object = element.getAsJsonObject();
        com.github.gerolndnr.connectionguard.core.http.DetectionFields.score(object, "risk");
        com.github.gerolndnr.connectionguard.core.http.DetectionFields.score(object, "confidence");
        if (object.has("classifications")) {
            com.google.gson.JsonObject types = object.getAsJsonObject("classifications");
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : types.entrySet()) {
                com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails.Type.valueOf(entry.getKey());
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid cached classification.");
            }
        }
    }
}
