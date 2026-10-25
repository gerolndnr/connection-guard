package com.github.gerolndnr.connectionguard.core.cloud;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Bounded private local state; never follow cloud/file symlinks or use predictable temporary files. */
final class CloudFiles {
    private CloudFiles() { }
    /** The plugin folder refused a write (full, read-only or not permitted). */
    static final class Unwritable extends IOException { Unwritable(IOException cause) { super("Cloud local state could not be written (values redacted).", cause); } }
    static void check(Path file) throws IOException {
        Path directory = file.getParent();
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent()) || Files.isSymbolicLink(file)
                || Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Cloud local state is invalid (values redacted).");
    }
    static byte[] read(Path file, int max) throws IOException {
        check(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (java.io.InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > max) throw new IOException("Cloud local state exceeds its bound (values redacted).");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    static void write(Path file, byte[] bytes) throws IOException {
        check(file);
        Path temp;
        try {
            Files.createDirectories(file.getParent()); check(file);
            try { temp = Files.createTempFile(file.getParent(), ".cg-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException unsupported) { temp = Files.createTempFile(file.getParent(), ".cg-", ".tmp"); }
        } catch (IOException refused) { throw new Unwritable(refused); }
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException refused) { throw new Unwritable(refused); }
        finally { Files.deleteIfExists(temp); }
    }
    static void delete(Path file) throws IOException { check(file); Files.deleteIfExists(file); }
}
