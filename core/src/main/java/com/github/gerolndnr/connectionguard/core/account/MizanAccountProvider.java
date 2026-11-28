package com.github.gerolndnr.connectionguard.core.account;

import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;
import com.github.gerolndnr.connectionguard.core.lookup.LookupException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.HttpUrl;
import okhttp3.Request;

import java.util.*;

/**
 * Mizan (mizan.ac, KORDU LTD): an index of public Minecraft ban lists, queried by account UUID
 * ({@code GET /v1/players/{uuid}}). Without a key Mizan answers its public view; the operator's own key
 * ({@code X-API-Key}) raises the limit. Only the account UUID is sent, never an address or a name.
 */
public final class MizanAccountProvider implements AccountProvider {
    public static final String ID = "mizan";
    /** The API contract this parser was written against (Mizan-Version header). */
    static final String API_VERSION = "2026-11-09";
    private static final HttpUrl ENDPOINT = HttpUrl.get("https://api.mizan.ac");
    private final HttpUrl base;
    private final String key;

    public MizanAccountProvider(String key) { this(key, null); }
    MizanAccountProvider(String key, HttpUrl fixture) {
        if (fixture != null && (!fixture.scheme().equals("http") || !fixture.host().equals("127.0.0.1") || fixture.query() != null))
            throw new IllegalArgumentException("Invalid loopback fixture endpoint.");
        this.key = key == null ? "" : key; this.base = fixture == null ? ENDPOINT : fixture;
    }

    @Override public String id() { return ID; }

    Request request(UUID uuid) {
        Request.Builder request = new Request.Builder()
                .url(base.newBuilder().addPathSegment("v1").addPathSegment("players").addPathSegment(uuid.toString()).build())
                .header("Accept", "application/json").header("Mizan-Version", API_VERSION);
        if (!key.isEmpty()) request.header("X-API-Key", key);
        return request.build();
    }

    @Override public AccountRecord check(UUID uuid) {
        JsonObject answer = ProviderHttp.readJson(request(uuid), ID).orElseThrow(() -> new LookupException(FailureReason.INVALID_RESPONSE));
        try { return parse(uuid, answer, System.currentTimeMillis()); }
        catch (RuntimeException invalid) { throw invalid instanceof LookupException ? invalid : new LookupException(FailureReason.INVALID_RESPONSE); }
    }

    static AccountRecord parse(UUID uuid, JsonObject answer, long now) {
        JsonObject data = object(answer, "data");
        String subject = uuid.toString().replace("-", "");
        if (data == null || !"minecraft".equals(text(data, "provider")) || !subject.equalsIgnoreCase(String.valueOf(text(data, "subject"))))
            throw new LookupException(FailureReason.INVALID_RESPONSE);
        JsonObject bans = object(data, "bans");
        if (bans == null) throw new LookupException(FailureReason.INVALID_RESPONSE);
        List<AccountRecord.Ban> active = new ArrayList<>();
        JsonObject record = object(data, "record");
        JsonArray list = record == null || !(record.get("bans") instanceof JsonArray) ? new JsonArray() : record.getAsJsonArray("bans");
        for (JsonElement item : list) {
            if (!item.isJsonObject() || !Boolean.TRUE.equals(bool(item.getAsJsonObject(), "active"))) continue;
            JsonObject ban = item.getAsJsonObject();
            JsonObject source = object(ban, "source");
            String name = source == null ? null : clean(text(source, "name"), 64);
            if (name == null) continue;
            active.add(new AccountRecord.Ban(name, keys(ban, "categories"), clean(text(ban, "reason"), 120),
                    instant(text(ban, "createdAt")), instant(text(ban, "expiresAt"))));
            if (active.size() == AccountRecord.SHOWN_BANS) break;
        }
        String profile = text(data, "profile");
        if (profile != null && !profile.startsWith("https://mizan.ac/")) profile = null;
        int total = count(bans, "total"), inForce = count(bans, "inForce");
        return new AccountRecord(ID, uuid, Math.max(total, inForce), inForce, count(bans, "listsInForce"), keys(bans, "categoriesInForce"), active, profile, now);
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
    private static String text(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static Boolean bool(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
    }
    private static int count(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new LookupException(FailureReason.INVALID_RESPONSE);
        long number = value.getAsLong();
        if (number < 0 || number > 1_000_000) throw new LookupException(FailureReason.INVALID_RESPONSE);
        return (int) number;
    }
    private static List<String> keys(JsonObject parent, String field) {
        List<String> out = new ArrayList<>();
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonArray()) return out;
        for (JsonElement item : value.getAsJsonArray())
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && item.getAsString().matches("[a-z][a-z0-9_-]{0,31}") && out.size() < 32)
                out.add(item.getAsString());
        return out;
    }
    private static Long instant(String value) {
        if (value == null) return null;
        try { return java.time.Instant.parse(value).toEpochMilli(); }
        catch (java.time.format.DateTimeParseException invalid) { return null; }
    }
    /** Third-party text shown in chat: no control characters, no colour codes, bounded. */
    static String clean(String value, int limit) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c) && c != '§' && Character.getType(c) != Character.FORMAT)
                .forEach(out::appendCodePoint);
        // Messages are deserialized with '&' colour codes: a list name must not change the colour of what follows.
        String text = out.toString().replaceAll("&(?=[0-9A-Fa-fK-Ok-oRrXx#])", "& ").trim();
        if (text.isEmpty()) return null;
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
