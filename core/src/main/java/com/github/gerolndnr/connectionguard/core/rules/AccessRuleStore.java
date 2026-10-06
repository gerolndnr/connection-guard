package com.github.gerolndnr.connectionguard.core.rules;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.policy.*;
import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** One immutable publication point. A versioned document owns policy fields and rules together. */
public final class AccessRuleStore {
    interface Writer { void write(Path path, byte[] bytes) throws IOException; }
    private final Path path;
    private final Object lock;
    private final Supplier<GuardSettings> base;
    private final Writer writer;
    private final Consumer<PolicyJournal> publicationGuard;
    private volatile State state = new State(Collections.emptyList(), null, 0, null);
    private GuardSettings nativeBase, cachedBase, cachedEffective;
    private State cachedState;
    private static final class State {
        final List<AccessRule> rules;
        final PolicyJournal journal;
        final long sequence;
        final String diskHash;
        State(List<AccessRule> rules, PolicyJournal journal, long sequence, String diskHash) {
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules)); this.journal = journal;
            this.sequence = sequence; this.diskHash = diskHash;
        }
    }
    public AccessRuleStore(Path directory) throws IOException { this(directory, new Object(), GuardSettings::defaults); }
    public AccessRuleStore(Path directory, Object lock, Supplier<GuardSettings> base) throws IOException {
        this(directory, lock, base, AccessRuleStore::atomicWrite, journal -> { });
    }
    public AccessRuleStore(Path directory, Object lock, Supplier<GuardSettings> base, Consumer<PolicyJournal> publicationGuard) throws IOException {
        this(directory, lock, base, AccessRuleStore::atomicWrite, publicationGuard);
    }
    AccessRuleStore(Path directory, Object lock, Supplier<GuardSettings> base, Writer writer) throws IOException {
        this(directory, lock, base, writer, journal -> { });
    }
    private AccessRuleStore(Path directory, Object lock, Supplier<GuardSettings> base, Writer writer, Consumer<PolicyJournal> publicationGuard) throws IOException {
        this.lock = Objects.requireNonNull(lock); this.base = Objects.requireNonNull(base); this.writer = Objects.requireNonNull(writer);
        this.publicationGuard = Objects.requireNonNull(publicationGuard);
        Files.createDirectories(directory); path = directory.resolve("access-rules.json"); nativeBase = base.get();
        if (Files.isSymbolicLink(path)) throw new IOException("Rules file must not be a symbolic link.");
        recoverStaging(directory);
        if (Files.exists(path)) reload();
    }
    /** Only our exact private staging namespace is disposable; the committed file and backup are never repaired from it. */
    private static void recoverStaging(Path directory) throws IOException {
        List<Path> abandoned = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, ".cg-policy-*.tmp")) {
            for (Path file : files) {
                if (!file.getFileName().toString().matches("\\.cg-policy-[0-9]+\\.tmp")) continue;
                if (abandoned.size() == 32 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Policy staging recovery requires operator review (values redacted).");
                abandoned.add(file);
            }
        }
        for (Path file : abandoned) Files.delete(file);
    }
    public void reload() throws IOException {
        synchronized (lock) {
            byte[] bytes = read();
            try {
                String text = new String(bytes, StandardCharsets.UTF_8).trim();
                PolicyJournal journal = null; List<AccessRule> rules;
                if (text.startsWith("[")) {
                    if (state.journal != null) throw new IllegalArgumentException("Versioned policy cannot be replaced by legacy input.");
                    AccessRule[] parsed = new Gson().fromJson(text, AccessRule[].class);
                    rules = Arrays.asList(Objects.requireNonNull(parsed));
                } else {
                    journal = PolicyJournal.read(new ByteArrayInputStream(bytes)); rules = journal.current().policy.rules;
                }
                validate(rules); publicationGuard.accept(journal);
                state = new State(rules, journal, Math.addExact(state.sequence, 1), hash(bytes));
            } catch (RuntimeException invalid) { throw new IOException("Invalid rules/policy document; active state preserved (values redacted)."); }
        }
    }
    private byte[] read() throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > PolicyJournal.MAX_BYTES)
            throw new IOException("Rules file is invalid or exceeds 1 MiB.");
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { if (result.size() + n > PolicyJournal.MAX_BYTES) throw new IOException("Rules file exceeds 1 MiB."); result.write(buffer, 0, n); }
            return result.toByteArray();
        }
    }
    private static void validate(List<AccessRule> rules) {
        if (rules.size() > 512) throw new IllegalArgumentException("At most 512 access rules are allowed.");
        Set<String> ids = new HashSet<>();
        for (AccessRule rule : rules) { if (rule == null) throw new IllegalArgumentException("Null rule."); rule.validate(); if (!ids.add(rule.getId())) throw new IllegalArgumentException("Duplicate rule ID."); }
    }
    public AccessRule add(AccessRule.Effect effect, AccessRule.Scope scope, String target, long expires, String reason) throws IOException {
        synchronized (lock) {
            AccessRule rule = new AccessRule("rule-" + UUID.randomUUID(), effect, scope, target, expires, reason);
            List<AccessRule> draft = new ArrayList<>(state.rules); draft.add(rule); saveRules(draft); return rule;
        }
    }
    public int pruneExpired(long now) throws IOException {
        synchronized (lock) {
            if (now < 0) throw new IllegalArgumentException("Invalid clock.");
            List<AccessRule> draft = new ArrayList<>(state.rules); int before = draft.size();
            draft.removeIf(rule -> rule.getExpiresAt() != 0 && now >= rule.getExpiresAt());
            int removed = before - draft.size(); if (removed > 0) saveRules(draft); return removed;
        }
    }
    public boolean remove(String id) throws IOException {
        synchronized (lock) {
            List<AccessRule> draft = new ArrayList<>(state.rules);
            if (!draft.removeIf(rule -> rule.getId().equals(id))) return false; saveRules(draft); return true;
        }
    }
    private void saveRules(List<AccessRule> draft) throws IOException {
        validate(draft);
        if (state.journal == null) commit(draft, null, new Gson().toJson(draft).getBytes(StandardCharsets.UTF_8));
        else {
            PolicyJournal next = state.journal.append(new PolicyReplay.Snapshot(effective(base.get()), draft), PolicyJournal.Operation.RULES,
                    state.journal.local, state.journal.configBase.settings, System.currentTimeMillis());
            commit(draft, next, next.bytes());
        }
    }
    public PolicyJournal transition(PolicyReplay.Snapshot candidate, PolicyJournal.Operation operation, boolean local, long asOf) throws IOException {
        synchronized (lock) {
            validate(candidate.rules); GuardSettings configured = base.get();
            PolicyJournal previous = state.journal == null ? PolicyJournal.baseline(configured, state.rules, asOf) : state.journal;
            PolicyJournal next = previous.append(candidate, operation, local, configured, asOf); byte[] bytes = next.bytes();
            publicationGuard.accept(next);
            ensureDiskState();
            // Preserve the actual legacy file once; never automatically restore stale rules from this backup.
            if (state.journal == null && state.diskHash != null) {
                Path backup = path.resolveSibling("access-rules.before-policy.json");
                if (Files.isSymbolicLink(backup) || Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Legacy backup must be a regular file.");
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) writer.write(backup, read());
            }
            commit(candidate.rules, next, bytes); return next;
        }
    }
    private void commit(List<AccessRule> rules, PolicyJournal journal, byte[] bytes) throws IOException {
        if (bytes.length > PolicyJournal.MAX_BYTES) throw new IOException("Rules file exceeds 1 MiB.");
        ensureDiskState();
        State next = new State(rules, journal, Math.addExact(state.sequence, 1), hash(bytes));
        writer.write(path, bytes); state = next; // No fallible I/O after the atomic rename/publication boundary.
    }
    private void ensureDiskState() throws IOException {
        if (state.diskHash == null ? Files.exists(path, LinkOption.NOFOLLOW_LINKS) : !state.diskHash.equals(hash(read())))
            throw new IOException("Rules changed outside the active state; reload explicitly before writing (values redacted).");
    }
    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(path) || Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Policy target must be a regular file.");
        Path temporary = Files.createTempFile(path.getParent(), ".cg-policy-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Platform ACLs apply on Windows. */ }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Never report a committed rename as failed. */ } }
    }
    private static String hash(byte[] bytes) {
        // Hash the exact bytes, independent of UTF-8 decoding of legacy input.
        try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(); }
    }
    public List<AccessRule> snapshot() { return state.rules; }
    public PolicyJournal journal() { return state.journal; }
    public boolean locallyOwned() { return state.journal != null && state.journal.local; }
    public String revision() { return state.journal == null ? "legacy-" + state.sequence : state.journal.current().id; }
    public GuardSettings effective(GuardSettings configured) {
        synchronized (lock) {
            if (!locallyOwned()) return configured;
            if (cachedState != state || cachedBase != configured) {
                cachedState = state; cachedBase = configured; cachedEffective = configured.withPolicy(state.journal.current().policy.settings);
            }
            return cachedEffective;
        }
    }
    public void validateConfigReload(GuardSettings next, boolean cloudManaged) {
        synchronized (lock) {
            try { ensureDiskState(); }
            catch (IOException changed) {
                throw new IllegalArgumentException("Rules/policy file changed; reload rules explicitly before changing config (values redacted).", changed);
            }
            if (locallyOwned() && (cloudManaged || !new PolicyReplay.Snapshot(nativeBase, Collections.emptyList()).fingerprint()
                    .equals(new PolicyReplay.Snapshot(next, Collections.emptyList()).fingerprint())))
                throw new IllegalArgumentException("Local policy owns decision settings; release its revision before changing config/dashboard policy (values redacted).");
        }
    }
    public void configured(GuardSettings next) { synchronized (lock) { nativeBase = next; } }
    public Optional<AccessRule> match(String ip, UUID uuid, boolean trusted, AccessRule.Scope scope) {
        List<AccessRule> selected = state.rules;
        for (AccessRule.Effect effect : new AccessRule.Effect[]{AccessRule.Effect.DENY, AccessRule.Effect.ALLOW, AccessRule.Effect.EXEMPT})
            for (AccessRule rule : selected) if (rule.getEffect() == effect && rule.matches(ip, uuid, trusted, scope, System.currentTimeMillis())) return Optional.of(rule);
        return Optional.empty();
    }
}
