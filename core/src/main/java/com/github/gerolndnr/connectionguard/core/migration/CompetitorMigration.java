package com.github.gerolndnr.connectionguard.core.migration;

import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Preview -> explicitly reviewed stage -> next-start commit. No live/login-path mutation. */
public final class CompetitorMigration {
    public static final List<String> SOURCES = Collections.unmodifiableList(Arrays.asList("foxgate", "proxyshield", "vpnguard", "kaurivpn", "advancedantivpn"));
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final byte[] template;
    public CompetitorMigration(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize(); MigrationFiles.path(this.directory);
        try (InputStream in = CompetitorMigration.class.getResourceAsStream("/config.yml")) {
            if (in == null) throw new IOException("CG config template unavailable.");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); template = out.toByteArray();
        }
    }
    public List<String> discover() throws IOException {
        List<String> result = new ArrayList<>();
        for (String source : SOURCES) if (sourceDirectory(source, false) != null) result.add(source);
        return Collections.unmodifiableList(result);
    }
    private Path sourceDirectory(String source, boolean required) throws IOException {
        if (!SOURCES.contains(source)) throw new IOException("Unsupported migration source.");
        Path parent = directory.getParent(); MigrationFiles.path(parent); List<Path> matches = new ArrayList<>();
        if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) try (DirectoryStream<Path> files = Files.newDirectoryStream(parent)) {
            for (Path file : files) if (file.getFileName().toString().equalsIgnoreCase(source)) {
                MigrationFiles.path(file);
                if (Files.isRegularFile(file.resolve("config.yml"), LinkOption.NOFOLLOW_LINKS)) matches.add(file);
            }
        }
        if (matches.size() > 1) throw new IOException("Ambiguous competitor directories; remove the duplicate before migration.");
        if (matches.isEmpty()) { if (required) throw new IOException("No local config found for " + source + "."); return null; }
        return matches.get(0);
    }
    public Plan preview(String source) throws IOException {
        Map<String, Object> defaults = MigrationFiles.yaml(template);
        Path config = directory.resolve("config.yml"); String configBefore = MigrationFiles.fingerprint(config);
        Map<String, Object> target = MigrationFiles.yaml(configBefore.equals("absent") ? template : MigrationFiles.read(config, MigrationFiles.MAX_BYTES));
        mergeDefaults(target, defaults);
        CompetitorImport imported = new CompetitorImport(source, sourceDirectory(source, true), defaults, target);
        String rulesBefore = MigrationFiles.fingerprint(directory.resolve("access-rules.json"));
        List<AccessRule> rules = existingRules(imported.blockers); imported.importAll();
        Set<String> existing = new HashSet<>(); for (AccessRule rule : rules) existing.add(rule.getEffect() + ":" + rule.getScope() + ":" + rule.getTarget());
        int added = 0;
        for (AccessRule rule : imported.rules) if (existing.add(rule.getEffect() + ":" + rule.getScope() + ":" + rule.getTarget())) { rules.add(rule); added++; }
        if (rules.size() > 512) imported.blockers.add("Combined admin rules exceed CG's 512-rule limit; consolidate explicitly first.");
        if (Files.exists(directory.resolve("cloud/managed-config.json"), LinkOption.NOFOLLOW_LINKS)) imported.blockers.add("A saved Cloud configuration overlay exists; release it before migration to avoid conflicting settings.");
        byte[] next = MigrationFiles.dump(target); validate(next, rules);
        // Detect files changing while parsing or querying admin DBs, including newly created files.
        for (Map.Entry<String, String> entry : imported.fingerprints.entrySet())
            if (!entry.getValue().equals(MigrationFiles.fingerprint(imported.directory.resolve(entry.getKey())))) throw new IOException("Competitor files changed during preview; retry after stopping it.");
        if (!configBefore.equals(MigrationFiles.fingerprint(config))) throw new IOException("CG config changed during preview; retry.");
        if (!rulesBefore.equals(MigrationFiles.fingerprint(directory.resolve("access-rules.json")))) throw new IOException("CG rules changed during preview; retry.");
        Plan plan = new Plan(); plan.source = source; plan.sourceDirectory = imported.directory.getFileName().toString();
        plan.sourceFiles.putAll(imported.fingerprints); plan.configBefore = configBefore; plan.rulesBefore = rulesBefore;
        plan.cloudBefore = MigrationFiles.fingerprint(directory.resolve("cloud/managed-config.json"));
        plan.config = new String(next, StandardCharsets.UTF_8); plan.rules = JSON.toJson(rules);
        plan.report.add("Migration source=" + source + "; new explicit admin rules=" + added + "; total=" + rules.size());
        plan.report.add("Changes: " + (imported.changes.isEmpty() ? "none" : String.join(", ", imported.changes)));
        List<String> recipients = new ArrayList<>();
        Object providers = MigrationFiles.get(target, "provider.vpn");
        for (String id : MigrationFiles.cast(providers).keySet()) if (!id.equals("local") && Boolean.TRUE.equals(MigrationFiles.get(target, "provider.vpn." + id + ".enabled"))) recipients.add(id);
        plan.report.add("Enabled external VPN recipients: " + String.join(", ", recipients) + "; Geo=" + MigrationFiles.get(target, "provider.geo.service"));
        plan.report.add("Mode=" + MigrationFiles.get(target, "operation.mode") + "; VPN failure=" + MigrationFiles.get(target, "failure-policy.vpn")
                + "; Geo failure=" + MigrationFiles.get(target, "failure-policy.geo") + "; existing customized CG fields win; DENY rules win over ALLOW.");
        plan.report.add("Cloud/Intel/default recipients retain your CG settings. See docs/PRIVACY.md and docs/PROVIDERS.md before apply. Blackbox includes hosting; zowi is run by FoxGate's developer; IP-API free is HTTP/non-commercial; ip-check has no published operator/terms/privacy.");
        plan.report.add("Country policy=" + MigrationFiles.get(target, "behavior.geo.type") + " " + MigrationFiles.get(target, "behavior.geo.list") + "; ALLOW rules=" + rules.stream().filter(rule -> rule.getEffect() == AccessRule.Effect.ALLOW).count() + "; DENY rules=" + rules.stream().filter(rule -> rule.getEffect() == AccessRule.Effect.DENY).count());
        plan.report.addAll(imported.warnings); plan.blockers.addAll(imported.blockers);
        plan.id = plan.digest();
        return plan;
    }
    @SuppressWarnings("unchecked") private static void mergeDefaults(Map<String, Object> current, Map<String, Object> defaults) {
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!current.containsKey(entry.getKey())) current.put(entry.getKey(), copy(entry.getValue()));
            else if (current.get(entry.getKey()) instanceof Map && entry.getValue() instanceof Map)
                mergeDefaults((Map<String, Object>) current.get(entry.getKey()), (Map<String, Object>) entry.getValue());
        }
    }
    private static Object copy(Object object) {
        if (object instanceof Map) { Map<String, Object> result = new LinkedHashMap<>();
            MigrationFiles.cast(object).forEach((key, value) -> result.put(key, copy(value))); return result; }
        if (object instanceof List) { List<Object> result = new ArrayList<>(); for (Object item : (List<?>) object) result.add(copy(item)); return result; }
        return object;
    }
    private List<AccessRule> existingRules(List<String> blockers) throws IOException {
        Path path = directory.resolve("access-rules.json"); List<AccessRule> result = new ArrayList<>();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return result;
        try {
            String text = new String(MigrationFiles.read(path, MigrationFiles.MAX_BYTES), StandardCharsets.UTF_8).trim();
            if (!text.startsWith("[")) { blockers.add("A versioned CG policy document exists; export/release that policy explicitly before merging competitor rules."); return result; }
            AccessRule[] rules = JSON.fromJson(text, AccessRule[].class); if (rules == null) throw new IllegalArgumentException();
            for (AccessRule rule : rules) { rule.validate(); result.add(rule); }
        } catch (RuntimeException invalid) { throw new IOException("Invalid existing admin rules; migration rejected (values redacted)."); }
        return result;
    }
    private void validate(byte[] config, List<AccessRule> rules) throws IOException {
        try {
            Map<String, Object> document = MigrationFiles.yaml(config);
            Map<String, Object> providers = MigrationFiles.cast(MigrationFiles.get(document, "provider.vpn"));
            ProviderConfiguration.forStartup(path -> MigrationFiles.get(document, path), new ArrayList<>(providers.keySet()), directory, null);
            if (rules.size() > 512) throw new IllegalArgumentException(); Set<String> ids = new HashSet<>();
            for (AccessRule rule : rules) { rule.validate(); if (!ids.add(rule.getId())) throw new IllegalArgumentException(); }
        } catch (RuntimeException invalid) { throw new IOException("Complete migration config/rules validation failed; nothing applied (values redacted)."); }
    }
    public static final class Plan {
        String id, source, sourceDirectory, configBefore, rulesBefore, cloudBefore, config, rules;
        Map<String, String> sourceFiles = new TreeMap<>();
        List<String> report = new ArrayList<>(), blockers = new ArrayList<>();
        public String id() { return id; }
        public List<String> report() { return Collections.unmodifiableList(report); }
        public List<String> blockers() { return Collections.unmodifiableList(blockers); }
        public boolean ready() { return blockers.isEmpty(); }
        private String digest() { JsonObject json = JSON.toJsonTree(this).getAsJsonObject(); json.remove("id");
            return MigrationFiles.hash(JSON.toJson(json).getBytes(StandardCharsets.UTF_8)).substring(0, 20); }
    }
    private Path pending() { return directory.resolve("migration/pending.json"); }
    /** The ID is a one-server preview token, not an authorization to mutate another server. */
    public void stage(Plan plan) throws IOException {
        if (!plan.ready()) throw new IOException("Resolve the displayed migration blockers before apply.");
        MigrationFiles.path(pending());
        if (Files.exists(pending(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("A migration is already staged; restart or cancel it first.");
        check(plan, false); validate(plan.config.getBytes(StandardCharsets.UTF_8), Arrays.asList(JSON.fromJson(plan.rules, AccessRule[].class)));
        MigrationFiles.write(pending(), JSON.toJson(plan).getBytes(StandardCharsets.UTF_8));
    }
    public void cancel() throws IOException {
        Path journal = directory.resolve("migration/commit.json"); MigrationFiles.path(journal); MigrationFiles.path(pending());
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Interrupted migration commit requires restart recovery; cannot cancel half of a transaction.");
        Files.deleteIfExists(pending());
    }
    private void check(Plan plan, boolean resume) throws IOException {
        if (plan == null || plan.id == null || !plan.id.matches("[0-9a-f]{20}") || !SOURCES.contains(plan.source)
                || plan.sourceDirectory == null || !plan.sourceDirectory.equalsIgnoreCase(plan.source) || !plan.ready()) throw new IOException("Invalid migration plan (values redacted).");
        if (!plan.id.equals(plan.digest())) throw new IOException("Migration plan changed after preview; preview again.");
        Path source = sourceDirectory(plan.source, true);
        if (!source.getFileName().toString().equals(plan.sourceDirectory)) throw new IOException("Source directory changed; preview again.");
        for (Map.Entry<String, String> entry : plan.sourceFiles.entrySet()) {
            // Only paths selected by the importer are accepted, never serialized arbitrary paths.
            String name = entry.getKey();
            if (name.startsWith("/") || name.contains("..") || name.contains("\\") || !name.matches("[a-zA-Z0-9_./-]{1,100}")) throw new IOException("Invalid source file in plan.");
            if (!entry.getValue().equals(MigrationFiles.fingerprint(source.resolve(name)))) throw new IOException("Source changed since preview; cancel and preview again.");
        }
        if (!plan.cloudBefore.equals(MigrationFiles.fingerprint(directory.resolve("cloud/managed-config.json")))) throw new IOException("Cloud settings changed since preview; cancel and preview again.");
        if (!resume && (!plan.configBefore.equals(MigrationFiles.fingerprint(directory.resolve("config.yml")))
                || !plan.rulesBefore.equals(MigrationFiles.fingerprint(directory.resolve("access-rules.json"))))) throw new IOException("CG files changed since preview; cancel and preview again.");
    }
    /** Called before any native config/cache/rule initialization. Durable roll-forward after a crash. */
    public void beforeStart(Consumer<String> notice) throws IOException {
        Path journal = directory.resolve("migration/commit.json"); MigrationFiles.path(journal); MigrationFiles.path(pending());
        boolean recovering = Files.exists(journal, LinkOption.NOFOLLOW_LINKS);
        if (recovering || Files.exists(pending(), LinkOption.NOFOLLOW_LINKS)) {
            Plan plan;
            try { plan = JSON.fromJson(new String(MigrationFiles.read(recovering ? journal : pending(), 4 * MigrationFiles.MAX_BYTES), StandardCharsets.UTF_8), Plan.class); }
            catch (RuntimeException invalid) { throw new IOException("Invalid staged migration; values redacted."); }
            if (plan == null || plan.id == null || !plan.id.matches("[0-9a-f]{20}") || !plan.id.equals(plan.digest())) throw new IOException("Staged migration was modified; values redacted.");
            if (recovering && !Objects.equals(plan.cloudBefore, MigrationFiles.fingerprint(directory.resolve("cloud/managed-config.json"))))
                throw new IOException("Cloud settings conflict with interrupted migration; resolve explicitly before starting.");
            // If a commit began, only the reviewed/validated transaction is recovered; source no longer matters.
            if (!recovering) {
                try { check(plan, false); ensureCompetitorRemoved(); }
                catch (IOException stale) { notice.accept("Migration not applied: " + stale.getMessage() + " Active CG files retained; /cg migrate cancel, then preview again."); return; }
            }
            List<AccessRule> rules;
            try { rules = Arrays.asList(JSON.fromJson(plan.rules, AccessRule[].class)); }
            catch (RuntimeException invalid) { throw new IOException("Invalid staged rules (values redacted)."); }
            validate(plan.config.getBytes(StandardCharsets.UTF_8), rules);
            String configNow = MigrationFiles.fingerprint(directory.resolve("config.yml")), rulesNow = MigrationFiles.fingerprint(directory.resolve("access-rules.json"));
            String configNext = MigrationFiles.hash(plan.config.getBytes(StandardCharsets.UTF_8)), rulesNext = MigrationFiles.hash(plan.rules.getBytes(StandardCharsets.UTF_8));
            if (!(configNow.equals(plan.configBefore) || configNow.equals(configNext)) || !(rulesNow.equals(plan.rulesBefore) || rulesNow.equals(rulesNext)))
                throw new IOException("Interrupted migration conflicts with external edits; preserve migration backups and resolve before starting.");
            Path backup = directory.resolve("migration/backups/" + plan.id);
            if (!recovering) {
                if (!plan.configBefore.equals("absent")) MigrationFiles.write(backup.resolve("config.yml"), MigrationFiles.read(directory.resolve("config.yml"), MigrationFiles.MAX_BYTES));
                if (!plan.rulesBefore.equals("absent")) MigrationFiles.write(backup.resolve("access-rules.json"), MigrationFiles.read(directory.resolve("access-rules.json"), MigrationFiles.MAX_BYTES));
                MigrationFiles.write(backup.resolve("report.txt"), (String.join("\n", plan.report) + "\nOriginal config=" + plan.configBefore + "; original rules=" + plan.rulesBefore + "\n").getBytes(StandardCharsets.UTF_8));
                MigrationFiles.write(journal, JSON.toJson(plan).getBytes(StandardCharsets.UTF_8));
            }
            if (!configNow.equals(configNext)) MigrationFiles.write(directory.resolve("config.yml"), plan.config.getBytes(StandardCharsets.UTF_8));
            if (!rulesNow.equals(rulesNext)) MigrationFiles.write(directory.resolve("access-rules.json"), plan.rules.getBytes(StandardCharsets.UTF_8));
            MigrationFiles.write(directory.resolve("migration/completed.json"), ("{\"id\":\"" + plan.id + "\",\"source\":\"" + plan.source + "\"}\n").getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(pending()); Files.delete(journal);
            notice.accept("Migration applied: " + plan.source + "; private originals/report: migration/backups/" + plan.id + ". Run /cg doctor and verify an allowed/blocked login.");
        }
        if (!Files.exists(directory.resolve("migration/completed.json"), LinkOption.NOFOLLOW_LINKS)) {
            List<String> sources = discover();
            if (!sources.isEmpty()) {
                String found = String.join(", ", sources); Path marker = directory.resolve("migration/discovery.txt");
                String before = Files.exists(marker, LinkOption.NOFOLLOW_LINKS) ? new String(MigrationFiles.read(marker, 1024), StandardCharsets.UTF_8) : "";
                if (!found.equals(before)) { notice.accept("Competitor config detected: " + found + ". Use /cg migrate preview <source>; no files, keys or IPs are imported automatically."); MigrationFiles.write(marker, found.getBytes(StandardCharsets.UTF_8)); }
            }
        }
    }
    private void ensureCompetitorRemoved() throws IOException {
        Path plugins = directory.getParent(); int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(plugins, "*.jar")) {
            for (Path file : files) {
                if (++count > 256) throw new IOException("Too many installed plugin archives to verify migration exclusivity.");
                MigrationFiles.path(file);
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    for (String descriptor : new String[]{"plugin.yml", "bungee.yml", "velocity-plugin.json", "paper-plugin.yml"}) {
                        ZipEntry entry = zip.getEntry(descriptor); if (entry == null) continue;
                        byte[] bytes;
                        try (InputStream in = zip.getInputStream(entry)) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
                            while ((n = in.read(buffer)) != -1) { if (out.size() + n > 32768) throw new IOException("Plugin descriptor too large."); out.write(buffer, 0, n); } bytes = out.toByteArray(); }
                        Map<String, Object> identity;
                        try {
                            identity = descriptor.endsWith(".json") ? MigrationFiles.cast(JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Map.class)) : MigrationFiles.yaml(bytes);
                            if (identity == null) throw new IllegalArgumentException();
                        } catch (RuntimeException invalid) { throw new IOException("Invalid installed plugin descriptor; verify it before migration."); }
                        for (String field : Arrays.asList("name", "id")) {
                            Object value = identity.get(field);
                            if (!(value instanceof String)) continue;
                            String normalized = ((String) value).toLowerCase(Locale.ROOT).replaceAll("[-_ ]", "");
                            if (SOURCES.contains(normalized) || normalized.equals("kauriantivpn") || normalized.equals("foxgatefree"))
                                throw new IOException("Remove competitor JARs before restart/apply; their independent login checks would still run.");
                        }
                        Object main = identity.get("main");
                        if (main instanceof String && ((String) main).startsWith("dev.brighten.antivpn."))
                            throw new IOException("Remove KauriVPN JAR before restart/apply; its independent login checks would still run.");
                    }
                }
            }
        }
    }
}
