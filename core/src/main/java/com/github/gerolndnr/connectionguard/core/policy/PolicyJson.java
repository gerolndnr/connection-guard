package com.github.gerolndnr.connectionguard.core.policy;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.*;

/** Strict bounded plain-data input. Duplicate keys, unknown fields and coercion are rejected. */
final class PolicyJson {
    private PolicyJson() { }
    static final int MAX_BYTES = 262144;
    static JsonObject read(InputStream input) throws IOException {
        return read(input, MAX_BYTES);
    }
    static JsonObject read(InputStream input, int maximum) throws IOException {
        if (maximum < 1 || maximum > 1048576) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int size;
        while ((size = input.read(buffer)) != -1) {
            if (bytes.size() + size > maximum) throw invalid();
            bytes.write(buffer, 0, size);
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        JsonElement value = value(reader, 0, new int[]{0});
        if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) throw invalid();
        return value.getAsJsonObject();
    }
    private static JsonElement value(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 16 || ++count[0] > 20000) throw invalid();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName(); if (key.length() > 100 || object.has(key)) throw invalid();
                    object.add(key, value(reader, depth + 1, count));
                }
                reader.endObject(); return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(value(reader, depth + 1, count));
                reader.endArray(); return array;
            case STRING:
                String text = reader.nextString(); if (text.length() > 4096) throw invalid(); return new JsonPrimitive(text);
            case NUMBER:
                String number = reader.nextString(); if (number.length() > 128) throw invalid();
                return new JsonPrimitive(new java.math.BigDecimal(number));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw invalid();
        }
    }
    static void fields(JsonObject object, String... names) {
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        for (String key : object.keySet()) if (!allowed.contains(key)) throw invalid();
    }
    static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }
    static String optionalText(JsonObject object, String key) { return !object.has(key) || object.get(key).isJsonNull() ? null : text(object, key); }
    static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid();
        return value.getAsBoolean();
    }
    static long number(JsonObject object, String key, long max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
        try { long result = value.getAsBigDecimal().longValueExact(); if (result < 0 || result > max) throw invalid(); return result; }
        catch (ArithmeticException bad) { throw invalid(); }
    }
    static JsonArray array(JsonObject object, String key, int maximum) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > maximum) throw invalid();
        return value.getAsJsonArray();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid policy replay input (values redacted)."); }
}
