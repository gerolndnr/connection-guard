package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** Once per staff member per installation, including reloads and clean restarts. Local only. */
final class CloudJoinNotices {
    private static final int MAX_STAFF = 4096, MAX_BYTES = 300000;
    private final Path file;
    private final Set<String> seen = new TreeSet<>();
    private boolean dirty;
    CloudJoinNotices(Path directory) { file = directory.resolve("staff-dashboard-notices-v1.json"); }
    synchronized void load() throws IOException {
        byte[] data = CloudFiles.read(file, MAX_BYTES);
        if (data == null) return;
        try {
            JsonObject json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            JsonArray entries = json.getAsJsonArray("seen");
            if (entries.size() > MAX_STAFF) throw new IllegalArgumentException();
            Set<String> loaded = new TreeSet<>();
            for (JsonElement entry : entries) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || !entry.getAsString().matches("[a-f0-9]{64}")
                        || !loaded.add(entry.getAsString())) throw new IllegalArgumentException();
            }
            seen.addAll(loaded);
        } catch (RuntimeException invalid) { throw new IOException("Dashboard notice state is invalid (values redacted)."); }
    }
    synchronized boolean reserve(UUID uuid) {
        if (uuid == null || seen.size() >= MAX_STAFF || !seen.add(hash(uuid))) return false;
        dirty = true; return true;
    }
    synchronized void save() throws IOException {
        if (!dirty) return;
        JsonObject json = new JsonObject(); json.addProperty("version", 1);
        JsonArray values = new JsonArray(); for (String hash : seen) values.add(hash); json.add("seen", values);
        CloudFiles.write(file, json.toString().getBytes(StandardCharsets.UTF_8)); dirty = false;
    }
    private static String hash(UUID uuid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("cg-dashboard-notice-v1:" + uuid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte part : digest) hex.append(String.format(Locale.ROOT, "%02x", part & 255)); return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
}
