package com.github.gerolndnr.connectionguard.core.migration;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Local input only. Errors intentionally omit YAML snippets, credentials and selectors. */
final class MigrationFiles {
    static final int MAX_BYTES = 1024 * 1024;
    private MigrationFiles() { }
    static void path(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Migration paths must not contain symbolic links.");
    }
    static byte[] read(Path path, int limit) throws IOException {
        path(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("Migration file missing, not regular, or too large (values redacted).");
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("Migration file too large."); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    static String fingerprint(Path path) throws IOException {
        path(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? hash(read(path, 32 * MAX_BYTES)) : "absent";
    }
    static String hash(byte[] bytes) {
        try { StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static Map<String, Object> yaml(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Migration YAML exceeds 1 MiB.");
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(24); options.setCodePointLimit(MAX_BYTES);
        try {
            Object result = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
            if (!(result instanceof Map)) throw new IllegalArgumentException();
            check(result, 0); return cast(result);
        } catch (RuntimeException invalid) { throw new IOException("Invalid migration YAML (values redacted)."); }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> cast(Object object) { return (Map<String, Object>) object; }
    private static void check(Object value, int depth) {
        if (depth > 24) throw new IllegalArgumentException();
        if (value instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String) || ((String) entry.getKey()).length() > 200) throw new IllegalArgumentException();
            check(entry.getValue(), depth + 1);
        } else if (value instanceof List) {
            if (((List<?>) value).size() > 2048) throw new IllegalArgumentException();
            for (Object element : (List<?>) value) check(element, depth + 1);
        } else if (value != null && !(value instanceof String) && !(value instanceof Boolean) && !(value instanceof Number)) throw new IllegalArgumentException();
    }
    static Object get(Map<String, Object> root, String path) {
        Object value = root;
        for (String key : path.split("\\.")) { if (!(value instanceof Map)) return null; value = ((Map<?, ?>) value).get(key); }
        return value;
    }
    static void set(Map<String, Object> root, String path, Object value) {
        String[] keys = path.split("\\."); Map<String, Object> current = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = current.get(keys[i]);
            if (next == null) { next = new LinkedHashMap<String, Object>(); current.put(keys[i], next); }
            if (!(next instanceof Map)) throw new IllegalArgumentException("Invalid target config shape.");
            current = cast(next);
        }
        current.put(keys[keys.length - 1], value);
    }
    static byte[] dump(Map<String, Object> root) {
        DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setIndent(2);
        return ("# Migrated by Connection Guard. Private: may contain provider API keys.\n"
                + "# Review docs/MIGRATIONS.md and docs/PROVIDERS.md for semantics, recipients and off switches.\n"
                + "# Enabled HTTP services receive player IPs on fallback. ProxyCheck, Blackbox (ipinfo.app),\n"
                + "# zowi (FoxGate developer), IPQuery, IP-API and explicitly selected keyed services may receive them.\n"
                + "# Blackbox has no written terms and includes hosting/cloud. ip-check.net has no published\n"
                + "# operator/terms/privacy and remains opt-in. IP-API free uses HTTP/non-commercial terms.\n"
                + "# Local Tor/Intel matching sends no player IP. Cloud is optional; disable cloud.enabled,\n"
                + "# /cg cloud disable or CONNECTIONGUARD_CLOUD=false. cloud.error-reports:false disables reports.\n"
                + "# Privacy and Cloud linking: https://connectionguard.net/privacy#plugin and docs/CLOUD.md.\n"
                + "# Default failover and local detection are retained; legacy cached verdicts are not rules.\n"
                + new Yaml(options).dump(root)).getBytes(StandardCharsets.UTF_8);
    }
    static void write(Path path, byte[] bytes) throws IOException {
        path(path); path(path.getParent()); Files.createDirectories(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Migration target must be a regular file.");
        Path temporary = Files.createTempFile(path.getParent(), ".cg-migration-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
