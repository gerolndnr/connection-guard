package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** Replays saved synthetic evidence only. Does not register hooks, query providers or activate settings. */
public final class PolicyReplay {
    /** RFC 5737 documentation address; live addresses are never included in saved observations. */
    public static final String REDACTED_ADDRESS = "192.0.2.1";
    public static final UUID REDACTED_UUID = new UUID(0, 0);
    private PolicyReplay() { }
    public static final class Snapshot {
        public final GuardSettings settings;
        public final List<AccessRule> rules;
        public Snapshot(GuardSettings settings, List<AccessRule> rules) {
            this.settings = Objects.requireNonNull(settings);
            if (rules.size() > 1024) throw PolicyJson.invalid();
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        }
        /** Stable identifier for the decision policy only; never includes provider keys or observed players. */
        public String fingerprint() {
            return digest(toJson().toString());
        }
        public boolean hasIdentityRules() {
            return rules.stream().anyMatch(rule -> !rule.isMetadata());
        }
        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("schema", 1); value.addProperty("mode", settings.observe ? "OBSERVE" : "ENFORCE");
            value.addProperty("vpn_failure", settings.vpnFailure.name()); value.addProperty("geo_failure", settings.geoFailure.name());
            value.addProperty("kick_vpn", settings.kickVpn); value.addProperty("kick_geo", settings.kickGeo);
            value.addProperty("geo_type", settings.geoWhitelist ? "WHITELIST" : "BLACKLIST");
            JsonArray countries = new JsonArray(); settings.countries.forEach(countries::add); value.add("countries", countries);
            JsonArray entries = new JsonArray();
            for (AccessRule rule : rules) {
                JsonObject entry = new JsonObject(); entry.addProperty("id", rule.getId());
                entry.addProperty("effect", rule.getEffect().name()); entry.addProperty("scope", rule.getScope().name());
                entry.addProperty("target", rule.getTarget()); entry.addProperty("expires_at", rule.getExpiresAt());
                entry.addProperty("reason", rule.getReason()); entries.add(entry);
            }
            value.add("rules", entries);
            return value;
        }
    }
    public static String digest(String text) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(64);
                for (byte octet : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", octet & 255));
                return result.toString();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    public static final class Case {
        public final String id;
        public final long observedAt;
        private final boolean identitiesRedacted;
        private final String ip, geoSource;
        private final UUID uuid;
        private final boolean trusted, vpnExempt, geoExempt;
        private final VpnResult vpn;
        private final GeoLookup geo;
        private Case(JsonObject object, boolean observed, boolean identitiesRedacted) {
            this.identitiesRedacted = identitiesRedacted;
            PolicyJson.fields(object, observed ? new String[]{"id", "ip", "uuid", "trusted", "vpn_exempt", "geo_exempt", "vpn", "geo", "observed_at"}
                    : new String[]{"id", "ip", "uuid", "trusted", "vpn_exempt", "geo_exempt", "vpn", "geo"});
            observedAt = observed ? PolicyJson.number(object, "observed_at", Long.MAX_VALUE) : 0;
            if (observed && observedAt == 0) throw PolicyJson.invalid();
            id = PolicyJson.text(object, "id"); if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw PolicyJson.invalid();
            ip = Exemptions.normalize(PolicyJson.text(object, "ip"));
            String identity = PolicyJson.optionalText(object, "uuid");
            uuid = identity == null ? null : UUID.fromString(identity);
            if (uuid != null && !uuid.toString().equals(identity)) throw PolicyJson.invalid();
            trusted = PolicyJson.bool(object, "trusted");
            if (trusted && uuid == null) throw PolicyJson.invalid();
            vpnExempt = PolicyJson.bool(object, "vpn_exempt"); geoExempt = PolicyJson.bool(object, "geo_exempt");
            vpn = vpn(ip, object.getAsJsonObject("vpn"), observed);
            JsonObject g = object.getAsJsonObject("geo");
            PolicyJson.fields(g, "source", "country", "isp", "asn", "reason", "duration_ms", "cached_at", "from_cache", "valid_until", "source_version");
            geoSource = PolicyJson.optionalText(g, "source");
            if (geoSource != null && !geoSource.matches("geo\\.[A-Za-z0-9_#.-]{1,96}")) throw PolicyJson.invalid();
            String country = PolicyJson.optionalText(g, "country");
            FailureReason reason = FailureReason.valueOf(PolicyJson.text(g, "reason"));
            // Live Disabled/exempt Geo inputs legitimately contain no country and no failure.
            // Preserve that absence in captures; synthetic inputs keep their existing strict contract.
            if (country != null && reason != FailureReason.NONE || country == null && reason == FailureReason.NONE && !observed)
                throw PolicyJson.invalid();
            long cachedAt = PolicyJson.number(g, "cached_at", Long.MAX_VALUE);
            long validUntil = PolicyJson.number(g, "valid_until", Long.MAX_VALUE);
            String sourceVersion = PolicyJson.optionalText(g, "source_version");
            if (sourceVersion != null && !sourceVersion.matches("[0-9a-f]{64}")) throw PolicyJson.invalid();
            GeoResult result = null;
            if (country != null) {
                result = new GeoResult(ip, country, "", Optional.ofNullable(PolicyJson.optionalText(g, "isp")).orElse("Unknown"));
                if (g.has("asn") && !g.get("asn").isJsonNull()) result.setAsn(PolicyJson.number(g, "asn", 4294967295L));
                result.setCachedOn(cachedAt);
                result.setValidUntil(validUntil);
                result.setSourceVersion(sourceVersion);
                if (geoSource == null) throw PolicyJson.invalid();
            } else if (g.has("asn") && !g.get("asn").isJsonNull() || PolicyJson.optionalText(g, "isp") != null) throw PolicyJson.invalid();
            geo = new GeoLookup(Optional.ofNullable(result), reason, PolicyJson.bool(g, "from_cache"), PolicyJson.number(g, "duration_ms", 3600000));
            if (identitiesRedacted && (!REDACTED_ADDRESS.equals(ip) || !Objects.equals(uuid, trusted ? REDACTED_UUID : null)))
                throw PolicyJson.invalid();
        }
        public ConnectionPolicy.Evaluation evaluate(Snapshot policy, long asOf) {
            if (identitiesRedacted && policy.hasIdentityRules()) throw new IllegalArgumentException("Redacted evidence cannot evaluate address/UUID rules.");
            return ConnectionPolicy.evaluate(policy.settings, policy.rules, ip, uuid, trusted, vpnExempt, geoExempt, vpn, geo, geoSource, asOf);
        }
        public List<ProviderVote> sources(long asOf) {
            return ConnectionPolicy.sources(LookupFreshness.vpn(vpn, asOf), LookupFreshness.geo(geo, asOf), geoSource);
        }
    }
    public static final class Cases {
        public final long capturedAt;
        public final boolean observed, identitiesRedacted;
        public final List<Case> cases;
        private Cases(long capturedAt, boolean observed, boolean identitiesRedacted, List<Case> cases) {
            this.capturedAt = capturedAt; this.observed = observed; this.identitiesRedacted = identitiesRedacted;
            this.cases = Collections.unmodifiableList(cases);
        }
    }
    public static Cases readCases(InputStream input) throws IOException {
        JsonObject object = PolicyJson.read(input);
        String kind = PolicyJson.text(object, "kind");
        boolean observed = kind.equals("saved_observations");
        boolean identitiesRedacted = observed && PolicyJson.bool(object, "identity_redacted");
        PolicyJson.fields(object, observed ? new String[]{"schema", "kind", "captured_at", "cases", "identity_redacted"}
                : new String[]{"schema", "kind", "captured_at", "cases"});
        if (PolicyJson.number(object, "schema", 1) != 1 || !(kind.equals("synthetic") || observed)) throw PolicyJson.invalid();
        List<Case> cases = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonElement item : PolicyJson.array(object, "cases", observed ? PolicyObservationCapture.MAX_SAMPLES : 64)) {
            Case entry = new Case(item.getAsJsonObject(), observed, identitiesRedacted);
            if (!ids.add(entry.id)) throw PolicyJson.invalid(); cases.add(entry);
        }
        if (cases.isEmpty()) throw PolicyJson.invalid();
        long capturedAt = PolicyJson.number(object, "captured_at", Long.MAX_VALUE);
        if (observed && capturedAt == 0) throw PolicyJson.invalid();
        return new Cases(capturedAt, observed, identitiesRedacted, cases);
    }

    static JsonObject capturedCase(String id, String ip, UUID uuid, boolean trusted, boolean vpnExempt, boolean geoExempt,
                                   VpnResult vpn, GeoLookup geo, String geoSource, long asOf, boolean redacted, boolean failover) {
        JsonObject entry = new JsonObject(); entry.addProperty("id", id); entry.addProperty("observed_at", asOf);
        entry.addProperty("ip", redacted ? REDACTED_ADDRESS : Exemptions.normalize(ip));
        UUID savedUuid = redacted ? (trusted ? REDACTED_UUID : null) : uuid;
        entry.add("uuid", nullable(savedUuid == null ? null : savedUuid.toString())); entry.addProperty("trusted", trusted);
        entry.addProperty("vpn_exempt", vpnExempt); entry.addProperty("geo_exempt", geoExempt);
        JsonObject vpnValue = new JsonObject(); vpnValue.addProperty("threshold", vpn.getPositiveThreshold());
        vpnValue.addProperty("status", vpn.getStatus().name()); vpnValue.addProperty("reason", vpn.getSourceReason().name());
        vpnValue.addProperty("strategy", failover ? "FAILOVER" : "CONSENSUS");
        vpnValue.addProperty("cached_at", vpn.getCachedOn()); vpnValue.addProperty("from_cache", vpn.isFromCache());
        vpnValue.addProperty("valid_until", vpn.getValidUntil()); vpnValue.add("source_version", nullable(vpn.getSourceVersion()));
        JsonArray votes = new JsonArray();
        for (ProviderVote vote : vpn.getVotes()) {
            JsonObject savedVote = new JsonObject(); savedVote.addProperty("source", vote.getProvider());
            savedVote.addProperty("status", vote.getStatus().name()); savedVote.addProperty("reason", vote.getReason().name());
            savedVote.addProperty("duration_ms", vote.getDurationMillis()); savedVote.addProperty("valid_until", vote.getValidUntil());
            savedVote.add("source_version", nullable(vote.getSourceVersion())); savedVote.addProperty("voting", vote.isVoting());
            savedVote.add("details", details(vote.getDetails())); votes.add(savedVote);
        }
        vpnValue.add("votes", votes); entry.add("vpn", vpnValue);
        JsonObject geoValue = new JsonObject(); geoValue.add("source", nullable(geoSource));
        GeoResult result = geo.getResult().orElse(null);
        geoValue.add("country", result == null ? JsonNull.INSTANCE : new JsonPrimitive(result.getCountryName()));
        geoValue.add("isp", result == null ? JsonNull.INSTANCE : new JsonPrimitive(result.getIspName()));
        geoValue.add("asn", result == null || result.getAsn() == null ? JsonNull.INSTANCE : new JsonPrimitive(result.getAsn()));
        geoValue.addProperty("reason", geo.getReason().name()); geoValue.addProperty("duration_ms", geo.getDurationMillis());
        geoValue.addProperty("cached_at", result == null ? 0 : result.getCachedOn());
        geoValue.addProperty("from_cache", geo.isCached()); geoValue.addProperty("valid_until", result == null ? 0 : result.getValidUntil());
        geoValue.add("source_version", nullable(result == null ? null : result.getSourceVersion())); entry.add("geo", geoValue);
        return entry;
    }

    private static JsonObject details(DetectionDetails details) {
        JsonObject value = new JsonObject(); JsonObject types = new JsonObject();
        details.getClassifications().forEach((type, enabled) -> types.addProperty(type.name(), enabled));
        if (!types.entrySet().isEmpty()) value.add("types", types);
        if (details.getAsn() != null) value.addProperty("asn", details.getAsn());
        if (details.getIsp() != null) value.addProperty("isp", details.getIsp());
        if (details.getOperator() != null) value.addProperty("operator", details.getOperator());
        if (details.getCountry() != null) value.addProperty("country", details.getCountry());
        BigDecimal risk = details.getExactRisk(); if (risk != null) value.addProperty("risk", risk);
        if (details.getConfidence() != null) value.addProperty("confidence", details.getConfidence());
        if (details.getDataAsOf() != null) value.addProperty("data_as_of", details.getDataAsOf());
        return value;
    }
    private static JsonElement nullable(String value) { return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value); }
    public static Snapshot readCandidate(InputStream input) throws IOException {
        return readCandidate(PolicyJson.read(input));
    }
    static Snapshot readCandidate(JsonObject object) {
        PolicyJson.fields(object, "schema", "mode", "vpn_failure", "geo_failure", "kick_vpn", "kick_geo", "geo_type", "countries", "rules");
        if (PolicyJson.number(object, "schema", 1) != 1) throw PolicyJson.invalid();
        Map<String, Object> fields = new HashMap<>();
        fields.put("operation.mode", PolicyJson.text(object, "mode"));
        fields.put("failure-policy.vpn", PolicyJson.text(object, "vpn_failure")); fields.put("failure-policy.geo", PolicyJson.text(object, "geo_failure"));
        fields.put("behavior.vpn.kick-player", PolicyJson.bool(object, "kick_vpn")); fields.put("behavior.geo.kick-player", PolicyJson.bool(object, "kick_geo"));
        fields.put("behavior.geo.type", PolicyJson.text(object, "geo_type"));
        List<String> countries = new ArrayList<>();
        for (JsonElement country : PolicyJson.array(object, "countries", 250)) {
            if (!country.isJsonPrimitive() || !country.getAsJsonPrimitive().isString()) throw PolicyJson.invalid(); countries.add(country.getAsString());
        }
        fields.put("behavior.geo.list", countries);
        List<AccessRule> rules = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonElement item : PolicyJson.array(object, "rules", 1024)) {
            JsonObject rule = item.getAsJsonObject();
            PolicyJson.fields(rule, "id", "effect", "scope", "target", "expires_at", "reason");
            AccessRule parsed = new AccessRule(PolicyJson.text(rule, "id"), AccessRule.Effect.valueOf(PolicyJson.text(rule, "effect")),
                    AccessRule.Scope.valueOf(PolicyJson.text(rule, "scope")), PolicyJson.text(rule, "target"),
                    PolicyJson.number(rule, "expires_at", Long.MAX_VALUE), PolicyJson.text(rule, "reason"));
            if (!ids.add(parsed.getId())) throw PolicyJson.invalid(); rules.add(parsed);
        }
        return new Snapshot(GuardSettings.read(fields::get, Collections.emptyList()), rules);
    }
    private static VpnResult vpn(String ip, JsonObject object, boolean observed) {
        PolicyJson.fields(object, observed ? new String[]{"threshold", "cached_at", "from_cache", "valid_until", "source_version", "votes", "status", "reason", "strategy"}
                : new String[]{"threshold", "cached_at", "from_cache", "valid_until", "source_version", "votes"});
        int threshold = (int) PolicyJson.number(object, "threshold", 16); if (threshold < 1) throw PolicyJson.invalid();
        String strategy = observed ? PolicyJson.text(object, "strategy") : "CONSENSUS";
        if (!(strategy.equals("CONSENSUS") || strategy.equals("FAILOVER")) || strategy.equals("FAILOVER") && threshold != 1) throw PolicyJson.invalid();
        List<ProviderVote> votes = new ArrayList<>(); Set<String> ids = new HashSet<>(); int voting = 0, positive = 0, complete = 0;
        for (JsonElement item : PolicyJson.array(object, "votes", 16)) {
            JsonObject vote = item.getAsJsonObject();
            PolicyJson.fields(vote, "source", "status", "reason", "duration_ms", "valid_until", "source_version", "voting", "details");
            ProviderVote parsed = new ProviderVote(PolicyJson.text(vote, "source"), ProviderVote.Status.valueOf(PolicyJson.text(vote, "status")),
                    FailureReason.valueOf(PolicyJson.text(vote, "reason")), PolicyJson.number(vote, "duration_ms", 3600000), details(vote.getAsJsonObject("details")),
                    PolicyJson.number(vote, "valid_until", Long.MAX_VALUE), PolicyJson.optionalText(vote, "source_version"), PolicyJson.bool(vote, "voting"));
            if (!ids.add(parsed.getProvider())) throw PolicyJson.invalid(); votes.add(parsed);
            if (parsed.isVoting()) { voting++; if (parsed.getStatus() != ProviderVote.Status.UNKNOWN) complete++; if (parsed.getStatus() == ProviderVote.Status.POSITIVE) positive++; }
        }
        VpnResult vpn = new VpnResult(ip, false);
        if (positive >= threshold) vpn.setStatus(ProviderVote.Status.POSITIVE);
        else if (voting > 0 && (strategy.equals("FAILOVER") ? complete > 0 : complete == voting)) vpn.setStatus(ProviderVote.Status.NEGATIVE);
        else vpn.setUnknown(voting == 0 ? FailureReason.NO_PROVIDER : FailureReason.NO_EVIDENCE);
        if (observed) {
            ProviderVote.Status savedStatus = ProviderVote.Status.valueOf(PolicyJson.text(object, "status"));
            FailureReason savedReason = FailureReason.valueOf(PolicyJson.text(object, "reason"));
            if ((savedStatus == ProviderVote.Status.UNKNOWN) != (savedReason != FailureReason.NONE)
                    || voting > 0 && savedStatus != vpn.getStatus()) throw PolicyJson.invalid();
            if (savedStatus == ProviderVote.Status.UNKNOWN) vpn.setUnknown(savedReason); else vpn.setStatus(savedStatus);
        }
        vpn.setPositiveThreshold(threshold); vpn.setVotes(votes);
        vpn.setCachedOn(PolicyJson.number(object, "cached_at", Long.MAX_VALUE)); vpn.setFromCache(PolicyJson.bool(object, "from_cache"));
        vpn.setValidUntil(PolicyJson.number(object, "valid_until", Long.MAX_VALUE)); vpn.setSourceVersion(PolicyJson.optionalText(object, "source_version"));
        return vpn;
    }
    private static DetectionDetails details(JsonObject object) {
        PolicyJson.fields(object, "types", "asn", "isp", "operator", "country", "risk", "confidence", "data_as_of");
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        if (object.has("types")) for (Map.Entry<String, JsonElement> type : object.getAsJsonObject("types").entrySet()) {
            if (!type.getValue().isJsonPrimitive() || !type.getValue().getAsJsonPrimitive().isBoolean()) throw PolicyJson.invalid();
            types.put(DetectionDetails.Type.valueOf(type.getKey()), type.getValue().getAsBoolean());
        }
        java.math.BigDecimal risk = null;
        if (object.has("risk") && !object.get("risk").isJsonNull()) {
            if (!object.get("risk").isJsonPrimitive() || !object.get("risk").getAsJsonPrimitive().isNumber()) throw PolicyJson.invalid();
            risk = object.get("risk").getAsBigDecimal();
        }
        Long asn = object.has("asn") && !object.get("asn").isJsonNull() ? PolicyJson.number(object, "asn", 4294967295L) : null;
        Integer confidence = object.has("confidence") && !object.get("confidence").isJsonNull() ? (int) PolicyJson.number(object, "confidence", 100) : null;
        Long dataAsOf = object.has("data_as_of") && !object.get("data_as_of").isJsonNull() ? PolicyJson.number(object, "data_as_of", Long.MAX_VALUE) : null;
        return DetectionDetails.withExactRisk(types, asn, PolicyJson.optionalText(object, "isp"), PolicyJson.optionalText(object, "operator"),
                PolicyJson.optionalText(object, "country"), risk, confidence).withDataAsOf(dataAsOf);
    }
}
