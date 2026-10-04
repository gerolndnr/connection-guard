package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** The anonymous install identity. Stored next to the config, owner-readable only, never logged. */
public final class CloudCredentials {
    public final String installId;
    public final String secret;
    public final String endpoint;
    final long sequence;

    CloudCredentials(String installId, String secret, String endpoint) {
        this(installId, secret, endpoint, 0);
    }
    private CloudCredentials(String installId, String secret, String endpoint, long sequence) {
        if (installId == null || !installId.matches("ins_[A-Za-z0-9]{20,32}")
                || secret == null || !secret.matches("cgs_[A-Za-z0-9_-]{40,64}") || sequence < 0)
            throw new IllegalArgumentException("Invalid cloud identity (values redacted).");
        CloudSettings.endpoint(endpoint);
        this.installId = installId; this.secret = secret; this.endpoint = endpoint; this.sequence = sequence;
    }

    CloudCredentials withSequence(long sequence) { return new CloudCredentials(installId, secret, endpoint, sequence); }

    String bearer() { return "Bearer " + installId + "." + secret; }

    static CloudCredentials load(Path file) {
        try {
            byte[] bytes = CloudFiles.read(file, 16 * 1024);
            if (bytes == null) return null;
            JsonObject json = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            String id = json.get("install_id").getAsString(), secret = json.get("secret").getAsString(), endpoint = json.get("endpoint").getAsString();
            if (!id.matches("ins_[A-Za-z0-9]{20,32}") || !secret.matches("cgs_[A-Za-z0-9_-]{40,64}")) return null;
            return new CloudCredentials(id, secret, endpoint, json.has("sequence") ? json.get("sequence").getAsLong() : 0);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    void save(Path file) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("install_id", installId);
        json.addProperty("secret", secret);
        json.addProperty("endpoint", endpoint);
        json.addProperty("sequence", sequence);
        CloudFiles.write(file, new Gson().toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    static void delete(Path file) {
        try { CloudFiles.delete(file); } catch (IOException ignored) { /* a stale file only causes a re-install */ }
    }
}
