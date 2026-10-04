package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import com.github.gerolndnr.connectionguard.core.policy.*;
import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Read-only local preview. Never dispatches live decision actions or changes an active draft. */
public final class PolicyCommands {
    private static volatile Path dataDirectory;
    private PolicyCommands() { }
    public static void configure(Path directory) { dataDirectory = directory.toAbsolutePath().normalize(); }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("policy")) return false;
        MessageCatalog messages = ConnectionGuard.getMessages();
        if (!permission.test("connectionguard.command.policy")) { reply.accept(messages.getString("ops.permission")); return true; }
        if (args.length < 2 || args.length > 4 || !args[1].equalsIgnoreCase("test")) {
            reply.accept(messages.getString("ops.policy-usage")); return true;
        }
        String casesName = args.length > 2 ? args[2] : "examples";
        try {
            PolicyReplay.Cases cases;
            try (InputStream input = casesName.equals("examples") ? PolicyCommands.class.getResourceAsStream("/policy/examples.json") : open(casesName)) {
                if (input == null) throw new IOException(); cases = PolicyReplay.readCases(input);
            }
            PolicyReplay.Snapshot active = ConnectionGuard.policySnapshot(), candidate = active;
            if (args.length == 4) try (InputStream input = open(args[3])) { candidate = PolicyReplay.readCandidate(input); }
            long asOf = System.currentTimeMillis();
            List<String> output = new ArrayList<>(); int changedOutcome = 0, changedFlags = 0, changedRules = 0;
            for (PolicyReplay.Case entry : cases.cases) {
                ConnectionPolicy.Evaluation old = entry.evaluate(active, asOf), next = entry.evaluate(candidate, asOf);
                if (!Objects.equals(old.denial, next.denial)) changedOutcome++;
                if (old.vpnFlag != next.vpnFlag || old.geoFlag != next.geoFlag) changedFlags++;
                if (!selected(old).equals(selected(next))) changedRules++;
                output.add(entry.id + ": " + outcome(old) + " -> " + outcome(next) + "; VPN=" + next.vpn.getStatus()
                        + " threshold=" + next.vpn.getPositiveThreshold() + " geo=" + next.geo.getReason()
                        + " flags=" + flags(next) + " rules=" + selected(old) + " -> " + selected(next));
                if (cases.cases.size() == 1) {
                    for (com.github.gerolndnr.connectionguard.core.lookup.ProviderVote source : entry.sources(asOf))
                        output.add("source=" + source.getProvider() + " status=" + source.getStatus() + " reason=" + source.getReason()
                                + " version=" + source.getSourceVersion() + " validUntil=" + source.getValidUntil());
                    for (EvidencePolicy.Decision trace : Arrays.asList(next.vpnRule, next.geoRule))
                        trace.getTrace().stream().limit(20).forEach(rule -> output.add("rule=" + rule.getRule().getId() + " scope=" + rule.getRule().getScope() + " match=" + rule.getMatch()));
                }
            }
            // Buffer the entire comparison before reporting success; malformed evidence produces no partial comparison.
            reply.accept(messages.text("ops.policy-summary", cases.cases.size(), changedOutcome, changedFlags, changedRules));
            reply.accept("asOf=" + java.time.Instant.ofEpochMilli(asOf) + " capturedAt=" + (cases.capturedAt == 0 ? "unavailable" : java.time.Instant.ofEpochMilli(cases.capturedAt))
                    + " ageMs=" + (cases.capturedAt == 0 || cases.capturedAt > asOf ? "unavailable" : asOf - cases.capturedAt));
            reply.accept(messages.getString("ops.policy-limits")); output.forEach(reply);
        } catch (IOException | RuntimeException invalid) { reply.accept(messages.getString("ops.policy-rejected")); }
        return true;
    }
    private static InputStream open(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_-]{1,64}") || dataDirectory == null) throw new IOException();
        Path directory = dataDirectory.resolve("policy"), file = directory.resolve(name + ".json");
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        Path actualRoot = dataDirectory.toRealPath(), actualDirectory = directory.toRealPath();
        if (!actualDirectory.getParent().equals(actualRoot) || Files.size(file) > 262144) throw new IOException();
        return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }
    private static String outcome(ConnectionPolicy.Evaluation value) { return value.denial == null ? "ALLOW" : "DENY(" + value.denial + ")"; }
    private static String flags(ConnectionPolicy.Evaluation value) { return "VPN:" + value.vpnFlag + ",GEO:" + value.geoFlag; }
    private static String selected(ConnectionPolicy.Evaluation value) {
        return "VPN:" + rule(value.vpnRule) + ",GEO:" + rule(value.geoRule);
    }
    private static String rule(EvidencePolicy.Decision rule) {
        return rule.getRule().map(value -> value.getId() + "/" + value.getEffect()).orElse(rule.isUnresolved() ? "unresolved" : "none");
    }
}
