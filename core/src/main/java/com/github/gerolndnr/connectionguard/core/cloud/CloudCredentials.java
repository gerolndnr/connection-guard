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

    CloudCredentials(String installId, String secret, String endpoint) {
        this.installId = installId; this.secret = secret; this.endpoint = endpoint;
    }

    String bearer() { return "Bearer " + installId + "." + secret; }

    static CloudCredentials load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
            String id = json.get("install_id").getAsString(), secret = json.get("secret").getAsString(), endpoint = json.get("endpoint").getAsString();
            if (!id.matches("ins_[A-Za-z0-9]{20,32}") || !secret.matches("cgs_[A-Za-z0-9_-]{40,64}")) return null;
            return new CloudCredentials(id, secret, endpoint);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("install_id", installId);
        json.addProperty("secret", secret);
        json.addProperty("endpoint", endpoint);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, new Gson().toJson(json).getBytes(StandardCharsets.UTF_8));
        try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException | IOException notPosix) { /* Windows: rely on the server directory's ACL */ }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void delete(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) { /* a stale file only causes a re-install */ }
    }
}
