package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One bounded, immutable disk document for decision fields, rules and rollback history. No runtime side effects. */
public final class PolicyJournal {
    public static final int MAX_REVISIONS = 3, MAX_BYTES = 1048576;
    public enum Operation { BASE, ACTIVATE, ROLLBACK, RELEASE, RULES }
    public final boolean local;
    public final PolicyReplay.Snapshot configBase;
    public final List<Revision> revisions;
    public static final class Revision {
        public final long number, createdAt;
        public final String id;
        public final Operation operation;
        public final PolicyReplay.Snapshot policy;
        private Revision(long number, long createdAt, Operation operation, PolicyReplay.Snapshot policy) {
            if (number < 0 || createdAt < 0 || policy.rules.size() > 512) throw PolicyJson.invalid();
            this.number = number; this.createdAt = createdAt; this.operation = Objects.requireNonNull(operation); this.policy = policy;
            id = "p" + number + "-" + policy.fingerprint().substring(0, 12);
        }
    }
    private PolicyJournal(boolean local, PolicyReplay.Snapshot configBase, List<Revision> revisions) {
        this.local = local; this.configBase = configBase;
        if (!configBase.rules.isEmpty() || revisions.isEmpty() || revisions.size() > MAX_REVISIONS) throw PolicyJson.invalid();
        Operation operation = revisions.get(0).operation;
        if (local && (operation == Operation.BASE || operation == Operation.RELEASE)
                || !local && (operation == Operation.ACTIVATE || operation == Operation.ROLLBACK)) throw PolicyJson.invalid();
        this.revisions = Collections.unmodifiableList(new ArrayList<>(revisions));
    }
    public static PolicyJournal baseline(GuardSettings base, List<AccessRule> rules, long asOf) {
        return new PolicyJournal(false, new PolicyReplay.Snapshot(base, Collections.emptyList()),
                Collections.singletonList(new Revision(0, asOf, Operation.BASE, new PolicyReplay.Snapshot(base, rules))));
    }
    public Revision current() { return revisions.get(0); }
    public Revision find(String id) { return revisions.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow(PolicyJson::invalid); }
    public PolicyJournal append(PolicyReplay.Snapshot policy, Operation operation, boolean local, GuardSettings base, long asOf) {
        long number = Math.addExact(current().number, 1);
        List<Revision> next = new ArrayList<>(); next.add(new Revision(number, asOf, operation, policy));
        revisions.stream().limit(MAX_REVISIONS - 1).forEach(next::add);
        return new PolicyJournal(local, new PolicyReplay.Snapshot(base, Collections.emptyList()), next);
    }
    public byte[] bytes() throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schema", 1); root.addProperty("local", local);
        root.add("config_base", configBase.toJson()); JsonArray history = new JsonArray();
        for (Revision revision : revisions) {
            JsonObject entry = new JsonObject(); entry.addProperty("number", revision.number); entry.addProperty("created_at", revision.createdAt);
            entry.addProperty("operation", revision.operation.name()); entry.addProperty("id", revision.id); entry.add("policy", revision.policy.toJson()); history.add(entry);
        }
        root.add("revisions", history); byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Policy history exceeds 1 MiB; active state preserved (values redacted).");
        // Validate our complete output before it can replace the existing file.
        read(new ByteArrayInputStream(bytes)); return bytes;
    }
    public static PolicyJournal read(InputStream input) throws IOException {
        JsonObject root = PolicyJson.read(input, MAX_BYTES); PolicyJson.fields(root, "schema", "local", "config_base", "revisions");
        if (PolicyJson.number(root, "schema", 1) != 1) throw PolicyJson.invalid();
        PolicyReplay.Snapshot base = PolicyReplay.readCandidate(root.getAsJsonObject("config_base"));
        List<Revision> revisions = new ArrayList<>(); long previous = Long.MAX_VALUE;
        for (JsonElement value : PolicyJson.array(root, "revisions", MAX_REVISIONS)) {
            JsonObject entry = value.getAsJsonObject(); PolicyJson.fields(entry, "number", "created_at", "operation", "id", "policy");
            long number = PolicyJson.number(entry, "number", Long.MAX_VALUE - 1);
            if (number >= previous) throw PolicyJson.invalid(); previous = number;
            Revision revision = new Revision(number, PolicyJson.number(entry, "created_at", Long.MAX_VALUE),
                    Operation.valueOf(PolicyJson.text(entry, "operation")), PolicyReplay.readCandidate(entry.getAsJsonObject("policy")));
            if (!revision.id.equals(PolicyJson.text(entry, "id"))) throw PolicyJson.invalid(); revisions.add(revision);
        }
        return new PolicyJournal(PolicyJson.bool(root, "local"), base, revisions);
    }
}
