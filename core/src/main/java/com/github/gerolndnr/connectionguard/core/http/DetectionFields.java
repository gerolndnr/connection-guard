package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Optional fields are absent on null, but malformed fields invalidate the source response. */
public final class DetectionFields {
    private DetectionFields() { }
    public static void address(JsonObject parent, String key, String requested) {
        String value = text(parent, key);
        if (value != null && !com.github.gerolndnr.connectionguard.core.identity.Exemptions.normalize(value).equals(
                com.github.gerolndnr.connectionguard.core.identity.Exemptions.normalize(requested))) throw new IllegalArgumentException("Response address mismatch.");
    }
    public static JsonObject object(JsonObject parent, String key) {
        JsonElement field = parent == null ? null : parent.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonObject()) throw new IllegalArgumentException("Invalid object field.");
        return field.getAsJsonObject();
    }
    public static String text(JsonObject parent, String key) {
        JsonElement field = parent == null ? null : parent.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid text field.");
        String value = field.getAsString().trim();
        if (value.isEmpty()) return null;
        if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl) || value.indexOf('\u00a7') >= 0) throw new IllegalArgumentException("Invalid text field.");
        return value;
    }
    public static Boolean bool(JsonObject parent, String key) {
        JsonElement field = parent == null ? null : parent.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid boolean field.");
        return field.getAsBoolean();
    }
    public static Integer score(JsonObject parent, String key) {
        BigDecimal value = decimalScore(parent, key);
        return value == null ? null : value.intValueExact();
    }
    public static BigDecimal decimalScore(JsonObject parent, String key) {
        JsonElement field = parent == null ? null : parent.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber() || field.getAsString().length() > 256)
            throw new IllegalArgumentException("Invalid decimal score field.");
        return DetectionDetails.normalizeRisk(new BigDecimal(field.getAsString()));
    }
    public static Long asn(JsonObject parent, String key, boolean organizationSuffix) {
        JsonElement field = parent == null ? null : parent.get(key);
        if (field == null || field.isJsonNull()) return null;
        if (!field.isJsonPrimitive()) throw new IllegalArgumentException("Invalid ASN field.");
        JsonPrimitive primitive = field.getAsJsonPrimitive();
        if (!primitive.isString() && !primitive.isNumber()) throw new IllegalArgumentException("Invalid ASN field.");
        String value = field.getAsString().trim();
        if (value.isEmpty()) return null;
        if (organizationSuffix && value.indexOf(' ') > 0) value = value.substring(0, value.indexOf(' '));
        if (value.startsWith("AS")) value = value.substring(2);
        if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid ASN field.");
        long asn = Long.parseLong(value);
        if (asn > 4294967295L) throw new IllegalArgumentException("Invalid ASN field.");
        return asn;
    }
    public static Map<DetectionDetails.Type, Boolean> types(JsonObject object, DetectionDetails.Type... supported) {
        Map<DetectionDetails.Type, Boolean> result = new EnumMap<>(DetectionDetails.Type.class);
        for (DetectionDetails.Type type : supported) {
            Boolean value = bool(object, type.name().toLowerCase(Locale.ROOT));
            if (value != null) result.put(type, value);
        }
        return result;
    }
}
