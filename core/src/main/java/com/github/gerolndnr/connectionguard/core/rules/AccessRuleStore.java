package com.github.gerolndnr.connectionguard.core.rules;

import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Validated bounded file, immutable snapshots, durable atomic replacement before activation. */
public final class AccessRuleStore {
    private final Path path;
    private volatile List<AccessRule> active = Collections.emptyList();
    public AccessRuleStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        path = directory.resolve("access-rules.json");
        if (Files.isSymbolicLink(path)) throw new IOException("Rules file must not be a symbolic link.");
        if (Files.exists(path)) reload();
    }
    public synchronized void reload() throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 1048576) throw new IOException("Rules file is invalid or exceeds 1 MiB.");
        try {
            AccessRule[] parsed = new Gson().fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), AccessRule[].class);
            List<AccessRule> draft = parsed == null ? Collections.emptyList() : Arrays.asList(parsed);
            validate(draft);
            active = Collections.unmodifiableList(new ArrayList<>(draft));
        } catch (RuntimeException invalid) { throw new IOException("Invalid rules file; active rules preserved."); }
    }
    private static void validate(List<AccessRule> rules) {
        if (rules.size() > 512) throw new IllegalArgumentException("At most 512 access rules are allowed.");
        Set<String> ids = new HashSet<>();
        for (AccessRule rule : rules) {
            if (rule == null) throw new IllegalArgumentException("Null rule.");
            rule.validate(); if (!ids.add(rule.getId())) throw new IllegalArgumentException("Duplicate rule ID.");
        }
    }
    public synchronized AccessRule add(AccessRule.Effect effect, AccessRule.Scope scope, String target, long expires, String reason) throws IOException {
        AccessRule rule = new AccessRule("rule-" + UUID.randomUUID(), effect, scope, target, expires, reason);
        List<AccessRule> draft = new ArrayList<>(active); draft.add(rule); save(draft); return rule;
    }
    public synchronized boolean remove(String id) throws IOException {
        List<AccessRule> draft = new ArrayList<>(active);
        if (!draft.removeIf(rule -> rule.getId().equals(id))) return false;
        save(draft); return true;
    }
    private void save(List<AccessRule> draft) throws IOException {
        validate(draft);
        if (Files.isSymbolicLink(path)) throw new IOException("Rules file must not be a symbolic link.");
        byte[] bytes = new Gson().toJson(draft).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1048576) throw new IOException("Rules file exceeds 1 MiB.");
        Path temporary = Files.createTempFile(path.getParent(), ".cg-rules-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Windows uses platform ACLs. */ }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            active = Collections.unmodifiableList(new ArrayList<>(draft));
        } finally { Files.deleteIfExists(temporary); }
    }
    public List<AccessRule> snapshot() { return active; }
    public Optional<AccessRule> match(String ip, UUID uuid, boolean trusted, AccessRule.Scope scope) {
        // Explicit deny > allow > exemption. Expired entries never affect access.
        for (AccessRule.Effect effect : new AccessRule.Effect[]{AccessRule.Effect.DENY, AccessRule.Effect.ALLOW, AccessRule.Effect.EXEMPT}) {
            for (AccessRule rule : active) if (rule.getEffect() == effect && rule.matches(ip, uuid, trusted, scope, System.currentTimeMillis())) return Optional.of(rule);
        }
        return Optional.empty();
    }
}
