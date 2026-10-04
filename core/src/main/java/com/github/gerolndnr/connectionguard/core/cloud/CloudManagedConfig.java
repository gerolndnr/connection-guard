package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Settings chosen in the dashboard. They are layered over config.yml in memory at load and
 * reload; config.yml itself is never written. Only the fields below may be managed, mirroring
 * CONFIG_FIELDS in the cloud protocol. Console commands, cache connections, identity, lookup
 * tuning and integrations can only ever come from config.yml.
 */
public final class CloudManagedConfig {
    enum Kind { BOOL, ENUM, INT, COUNTRIES, LIST, SECRET, SECRET_URL }

    static final class Field {
        final Kind kind; final List<String> options; final int min, max;
        Field(Kind kind, List<String> options, int min, int max) { this.kind = kind; this.options = options; this.min = min; this.max = max; }
    }

    static final Map<String, Field> FIELDS;
    static {
        Map<String, Field> f = new LinkedHashMap<>();
        f.put("operation.mode", e("OBSERVE", "ENFORCE"));
        f.put("failure-policy.vpn", e("OPEN", "CLOSED", "OBSERVE"));
        f.put("failure-policy.geo", e("OPEN", "CLOSED", "OBSERVE"));
        f.put("required-positive-flags", new Field(Kind.INT, null, 1, 16));
        f.put("provider.vpn.proxycheck.enabled", b());
        f.put("provider.vpn.proxycheck.api-key", new Field(Kind.SECRET, null, 0, 0));
        f.put("provider.vpn.ip-api.enabled", b());
        f.put("provider.vpn.iphub.enabled", b());
        f.put("provider.vpn.iphub.api-key", new Field(Kind.SECRET, null, 0, 0));
        f.put("provider.vpn.vpnapi.enabled", b());
        f.put("provider.vpn.vpnapi.api-key", new Field(Kind.SECRET, null, 0, 0));
        f.put("provider.geo.service", e("IP-API", "ProxyCheck", "Local", "Disabled"));
        f.put("provider.cache.expiration.vpn", new Field(Kind.INT, null, 1, 525_600));
        f.put("provider.cache.expiration.geo", new Field(Kind.INT, null, 1, 525_600));
        for (String scope : new String[]{"vpn", "geo"}) {
            f.put("behavior." + scope + ".kick-player", b());
            f.put("behavior." + scope + ".notify-staff", b());
            f.put("behavior." + scope + ".send-webhook.enabled", b());
            f.put("behavior." + scope + ".send-webhook.url", new Field(Kind.SECRET_URL, null, 0, 0));
            f.put("behavior." + scope + ".exemptions", new Field(Kind.LIST, null, 200, 64));
        }
        f.put("behavior.geo.type", e("BLACKLIST", "WHITELIST"));
        f.put("behavior.geo.list", new Field(Kind.COUNTRIES, null, 0, 250));
        FIELDS = Collections.unmodifiableMap(f);
    }
    private static Field b() { return new Field(Kind.BOOL, null, 0, 0); }
    private static Field e(String... options) { return new Field(Kind.ENUM, Arrays.asList(options), 0, 0); }

    static boolean isSecret(String path) { Field f = FIELDS.get(path); return f != null && (f.kind == Kind.SECRET || f.kind == Kind.SECRET_URL); }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Object LOCK = new Object();
    /** Native local/remote preparation is serialized here, independently of admission. */
    public static Object reloadLock() { return LOCK; }

    final int version;
    /** Typed values ready for a YAML document: Boolean, Integer, String or List<String>. */
    final Map<String, Object> values;

    CloudManagedConfig(int version, Map<String, Object> values) { this.version = version; this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }

    static final CloudManagedConfig EMPTY = new CloudManagedConfig(0, new LinkedHashMap<>());

    static Path file(Path dataDir) { return dataDir.resolve("cloud").resolve("managed-config.json"); }

    /** A missing overlay uses config.yml; an invalid existing file rejects the complete native draft. */
    static CloudManagedConfig load(Path dataDir) {
        try {
            byte[] bytes = CloudFiles.read(file(dataDir), 128 * 1024);
            if (bytes == null) return EMPTY;
            JsonObject json = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            int version = json.get("version").getAsInt();
            if (version < 0) throw new IllegalArgumentException();
            return new CloudManagedConfig(version, parse(json.getAsJsonObject("values")));
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalArgumentException("Cloud settings file is invalid; active settings preserved (values redacted).");
        }
    }

    /**
     * Layers the dashboard values over a freshly loaded config.yml document. Called by every
     * platform on load and reload, before validation, so invalid values are rejected exactly like
     * an invalid config.yml and the running configuration stays active.
     */
    public static void overlay(Path dataDir, BiConsumer<String, Object> set) {
        synchronized (LOCK) { load(dataDir).values.forEach(set); }
    }

    /** Validates and converts a dashboard value map. Unknown paths are refused, never ignored. */
    static Map<String, Object> parse(JsonObject values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) return out;
        if (values.size() > FIELDS.size()) throw new IllegalArgumentException("Too many settings (values redacted).");
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            String path = entry.getKey();
            Field field = FIELDS.get(path);
            if (field == null) throw new IllegalArgumentException("Setting cannot be set from the dashboard (path/value redacted).");
            out.put(path, convert(path, field, entry.getValue()));
        }
        return out;
    }

    private static Object convert(String path, Field f, JsonElement v) {
        String bad = path + " has an invalid value.";
        if (v == null || v.isJsonNull()) throw new IllegalArgumentException(bad);
        switch (f.kind) {
            case BOOL:
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(bad);
                return v.getAsBoolean();
            case ENUM:
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !f.options.contains(v.getAsString())) throw new IllegalArgumentException(bad);
                return v.getAsString();
            case INT: {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(bad);
                double d = v.getAsDouble();
                if (d != Math.rint(d) || d < f.min || d > f.max) throw new IllegalArgumentException(bad);
                return (int) d;
            }
            case COUNTRIES:
            case LIST: {
                if (!v.isJsonArray() || v.getAsJsonArray().size() > (f.kind == Kind.LIST ? f.min : f.max)) throw new IllegalArgumentException(bad);
                List<String> items = new ArrayList<>();
                for (JsonElement item : v.getAsJsonArray()) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(bad);
                    String s = item.getAsString();
                    boolean ok = f.kind == Kind.COUNTRIES ? s.matches("[A-Z]{2}") : s.length() <= f.max && !s.trim().isEmpty() && !s.contains("\n") && !s.contains("\r");
                    if (!ok) throw new IllegalArgumentException(bad);
                    items.add(f.kind == Kind.LIST ? s.trim() : s);
                }
                return items;
            }
            case SECRET:
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !v.getAsString().matches("[A-Za-z0-9._:-]{0,256}")) throw new IllegalArgumentException(bad);
                return v.getAsString();
            case SECRET_URL:
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !(v.getAsString().isEmpty() || v.getAsString().matches("https://\\S{1,504}"))) throw new IllegalArgumentException(bad);
                return v.getAsString();
            default:
                throw new IllegalArgumentException(bad);
        }
    }

    /** Strict protocol-v1 envelope, independently enforced even for a compromised backend. */
    static int desiredVersion(JsonObject desired) {
        JsonElement version = desired.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) throw malformed();
        try {
            int value = version.getAsBigDecimal().intValueExact();
            if (value < 1) throw malformed();
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) { throw malformed(); }
    }
    static CloudManagedConfig desired(CloudManagedConfig current, JsonObject desired) {
        if (!desired.keySet().equals(new HashSet<>(Arrays.asList("version", "reset", "values", "keep_secrets")))) throw malformed();
        int version = desiredVersion(desired);
        JsonElement reset = desired.get("reset"), values = desired.get("values"), keep = desired.get("keep_secrets");
        if (!reset.isJsonPrimitive() || !reset.getAsJsonPrimitive().isBoolean() || !values.isJsonObject() || !keep.isJsonArray()) throw malformed();
        return next(current, version, reset.getAsBoolean(), values.getAsJsonObject(), keep.getAsJsonArray());
    }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed settings (values redacted)."); }

    /** Builds the next overlay from a desired config, carrying over secrets the plugin already holds. */
    static CloudManagedConfig next(CloudManagedConfig current, int version, boolean reset, JsonObject values, JsonArray keepSecrets) {
        Map<String, Object> parsed = parse(values);
        if (keepSecrets != null && keepSecrets.size() > FIELDS.size()) throw malformed();
        if (keepSecrets != null) for (JsonElement keep : keepSecrets) {
            if (!keep.isJsonPrimitive() || !keep.getAsJsonPrimitive().isString() || !isSecret(keep.getAsString()))
                throw new IllegalArgumentException("Invalid secret selection (values redacted).");
        }
        Map<String, Object> next = new LinkedHashMap<>();
        if (!reset) {
            if (keepSecrets != null && keepSecrets.size() > FIELDS.size()) throw new IllegalArgumentException("Too many secret selections.");
            if (keepSecrets != null) for (JsonElement keep : keepSecrets) {
                if (!keep.isJsonPrimitive() || !keep.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid secret selection.");
                String path = keep.getAsString();
                if (!isSecret(path)) throw new IllegalArgumentException("Not a secret setting (path/value redacted).");
                if (current.values.containsKey(path)) next.put(path, current.values.get(path));
            }
            next.putAll(parsed);
        }
        return new CloudManagedConfig(version, next);
    }

    /** Swaps the overlay file atomically; returns the previous bytes (or null) for rollback. */
    static byte[] write(Path dataDir, CloudManagedConfig config) throws IOException {
        synchronized (LOCK) {
            Path file = file(dataDir);
            byte[] previous = CloudFiles.read(file, 128 * 1024);
            JsonObject json = new JsonObject();
            json.addProperty("version", config.version);
            json.add("values", GSON.toJsonTree(config.values));
            byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 128 * 1024) throw new IOException("Cloud settings exceed their bound.");
            CloudFiles.write(file, bytes);
            return previous;
        }
    }

    static void restore(Path dataDir, byte[] previous) throws IOException {
        synchronized (LOCK) {
            Path file = file(dataDir);
            if (previous == null) CloudFiles.delete(file); else CloudFiles.write(file, previous);
        }
    }

    /** Effective values for the dashboard. Secrets are reported only as "set" plus their last four characters. */
    static JsonObject snapshot(Function<String, Object> effective) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Field> entry : FIELDS.entrySet()) {
            Object value;
            try { value = effective.apply(entry.getKey()); } catch (RuntimeException unreadable) { continue; }
            Field f = entry.getValue();
            if (f.kind == Kind.SECRET || f.kind == Kind.SECRET_URL) {
                String s = value == null ? "" : value.toString();
                JsonObject secret = new JsonObject();
                secret.addProperty("set", !s.isEmpty());
                secret.addProperty("hint", s.length() >= 8 ? s.substring(s.length() - 4) : null);
                out.add(entry.getKey(), secret);
            } else if (value instanceof Boolean && f.kind == Kind.BOOL) out.addProperty(entry.getKey(), (Boolean) value);
            else if (value instanceof Number && f.kind == Kind.INT) out.addProperty(entry.getKey(), ((Number) value).intValue());
            else if (value instanceof String && f.kind == Kind.ENUM) out.addProperty(entry.getKey(), (String) value);
            else if (value instanceof Collection && (f.kind == Kind.LIST || f.kind == Kind.COUNTRIES)) {
                JsonArray items = new JsonArray();
                for (Object item : (Collection<?>) value) if (item != null && items.size() < 250) items.add(item.toString().length() > 64 ? item.toString().substring(0, 64) : item.toString());
                out.add(entry.getKey(), items);
            }
        }
        return out;
    }
}
