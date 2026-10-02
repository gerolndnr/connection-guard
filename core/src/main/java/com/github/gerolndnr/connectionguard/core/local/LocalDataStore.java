package com.github.gerolndnr.connectionguard.core.local;

import com.google.gson.Gson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Content-addressed generations and one atomic manifest. Failed validation never replaces the manifest. */
public final class LocalDataStore {
    private final Path root, inbox;
    private final Map<String, LocalSource> sources = new LinkedHashMap<>();
    private static final Gson JSON = new Gson();
    private static final class Manifest {
        int schema = 1;
        String version, settings, timeBasis;
        long dataTime, fetchedAt;
        int bytes;
    }
    public LocalDataStore(Path directory, List<LocalSource> configured) {
        if (directory == null) throw new IllegalArgumentException("Local sources require the plugin data directory.");
        root = directory.toAbsolutePath().normalize().resolve("local-data"); inbox = root.resolve("inbox");
        checkParents(root); checkParents(inbox);
        for (LocalSource source : configured) sources.put(source.id, source);
    }
    public List<LocalSource> sources() { return Collections.unmodifiableList(new ArrayList<>(sources.values())); }
    public LocalSource source(String id) {
        LocalSource source = sources.get(id);
        if (source == null) throw new IllegalArgumentException("Unknown local source ID.");
        return source;
    }
    public synchronized LocalSnapshot load(String id, long now) throws IOException {
        LocalSource source = source(id); Path manifestPath = root.resolve(id + ".json");
        checkParents(manifestPath);
        if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) return LocalSnapshot.missing(source);
        try {
            com.google.gson.JsonObject raw = com.google.gson.JsonParser.parseString(new String(read(manifestPath, 8192), StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> fields = new HashSet<>(Arrays.asList("schema", "version", "settings", "timeBasis", "dataTime", "fetchedAt", "bytes"));
            if (!raw.keySet().equals(fields)) throw new IllegalArgumentException();
            for (String field : Arrays.asList("schema", "dataTime", "fetchedAt", "bytes")) {
                com.google.gson.JsonElement element = raw.get(field);
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || !element.getAsString().matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
                Long.parseLong(element.getAsString());
            }
            if (raw.get("schema").getAsLong() != 1 || raw.get("bytes").getAsLong() > limit(source)) throw new IllegalArgumentException();
            for (String field : Arrays.asList("version", "settings", "timeBasis")) if (!raw.get(field).isJsonPrimitive() || !raw.get(field).getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            Manifest manifest = JSON.fromJson(raw, Manifest.class);
            if (manifest == null || manifest.schema != 1 || manifest.version == null || !manifest.version.matches("[0-9a-f]{64}")
                    || !source.attributionFingerprint().equals(manifest.settings) || manifest.bytes < 1 || manifest.timeBasis == null) throw new IllegalArgumentException();
            byte[] bytes = read(root.resolve(id + "." + manifest.version + ".data"), limit(source));
            if (bytes.length != manifest.bytes || !LocalSource.hash(bytes).equals(manifest.version)) throw new IllegalArgumentException();
            LocalSnapshot snapshot = LocalSnapshot.parse(source, bytes, manifest.dataTime, manifest.fetchedAt, manifest.timeBasis, now);
            if (snapshot.dataTime != manifest.dataTime || !snapshot.timeBasis.equals(manifest.timeBasis)) throw new IllegalArgumentException();
            return snapshot;
        } catch (RuntimeException invalid) { throw new IOException("Local manifest or content is invalid; active data preserved (values redacted)."); }
    }
    public synchronized LocalSnapshot importFile(String id, String fileName, long asOf, long now) throws IOException {
        if (fileName == null || !fileName.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}") || fileName.contains("..")) throw new IllegalArgumentException("Use a plain filename inside local-data/inbox.");
        LocalSource source = source(id);
        return commit(source, read(inbox.resolve(fileName), limit(source)), asOf, now, "IMPORT_AS_OF", now);
    }
    synchronized LocalSnapshot downloaded(String id, byte[] bytes, long asOf, String basis, long now) throws IOException {
        LocalSource source = source(id);
        if (source.isDatabase()) throw new IllegalArgumentException("MMDB data must be imported by the operator.");
        return commit(source, bytes, asOf, now, basis, now);
    }
    private LocalSnapshot commit(LocalSource source, byte[] bytes, long asOf, long fetchedAt, String basis, long now) throws IOException {
        LocalSnapshot checked = LocalSnapshot.parse(source, bytes, asOf, fetchedAt, basis, now);
        createPrivateDirectories();
        Path manifestPath = root.resolve(source.id + ".json"); checkParents(manifestPath);
        Path content = root.resolve(source.id + "." + checked.version + ".data");
        checkParents(content);
        boolean existed = Files.exists(content, LinkOption.NOFOLLOW_LINKS);
        // Rewriting identical content is safe. It does not change the MMDB's embedded build date.
        writeAtomic(content, bytes);
        Manifest manifest = new Manifest(); manifest.version = checked.version; manifest.settings = source.attributionFingerprint();
        manifest.timeBasis = checked.timeBasis; manifest.dataTime = checked.dataTime; manifest.fetchedAt = checked.fetchedAt; manifest.bytes = bytes.length;
        try { writeAtomic(manifestPath, JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException failure) { if (!existed) try { Files.deleteIfExists(content); } catch (IOException ignored) { /* Preserve original failure. */ } throw failure; }
        // Readers hold immutable heap snapshots, so obsolete generation files need not accumulate on disk.
        try (DirectoryStream<Path> old = Files.newDirectoryStream(root, source.id + ".*.data")) {
            for (Path entry : old) if (!entry.equals(content) && entry.getFileName().toString().matches(source.id + "\\.[0-9a-f]{64}\\.data")
                    && !Files.isSymbolicLink(entry)) try { Files.deleteIfExists(entry); } catch (IOException ignored) { /* New manifest already committed. */ }
        } catch (IOException cleanup) { /* Publication succeeded. A cleanup failure must not report a failed import. */ }
        return checked;
    }
    private void createPrivateDirectories() throws IOException {
        checkParents(root); checkParents(inbox);
        Files.createDirectories(inbox);
        privateMode(root, "rwx------"); privateMode(inbox, "rwx------");
    }
    public void prepareInbox() throws IOException { createPrivateDirectories(); }
    private static int limit(LocalSource source) { return source.isDatabase() ? BoundedMmdb.MAX_BYTES : 4 * 1024 * 1024; }
    static byte[] read(Path path, int limit) throws IOException {
        checkParents(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw new IOException("Local file is missing, invalid or exceeds its size bound (path redacted).");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { return readBounded(input, limit); }
    }
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Local data exceeds its size bound.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static void checkParents(Path path) {
        for (Path next = path.toAbsolutePath().normalize(); next != null; next = next.getParent()) if (Files.isSymbolicLink(next)) throw new IllegalArgumentException("Local data paths must not contain symbolic links.");
    }
    private static void privateMode(Path path, String mode) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); }
        catch (UnsupportedOperationException ignored) { /* Platform ACLs on Windows. */ }
    }
    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        checkParents(target);
        Path temp = Files.createTempFile(target.getParent(), ".cg-local-", ".tmp");
        try {
            privateMode(temp, "rw-------");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* Do not hide publication outcome. */ } }
    }
}
