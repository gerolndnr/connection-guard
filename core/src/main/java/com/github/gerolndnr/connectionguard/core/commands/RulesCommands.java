package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.rules.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Persistent administrator changes are explicit, scoped and atomic; no player-name resolution. */
public final class RulesCommands {
    public static final List<String> NAMES = Arrays.asList("allow", "deny", "exempt");
    private RulesCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        if (args.length == 0 || !NAMES.contains(args[0].toLowerCase(Locale.ROOT))) return false;
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!permission.test("connectionguard.command." + name)) { reply.accept(messages.getString("ops.permission")); return true; }
        AccessRuleStore store = ConnectionGuard.getRuleStore();
        if (store == null) { reply.accept(messages.getString("ops.rules-unavailable")); return true; }
        AccessRule.Effect effect = name.equals("allow") ? AccessRule.Effect.ALLOW : name.equals("deny") ? AccessRule.Effect.DENY : AccessRule.Effect.EXEMPT;
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            int displayed = 0;
            for (AccessRule rule : store.snapshot()) if (rule.getEffect() == effect && (rule.getExpiresAt() == 0 || rule.getExpiresAt() > System.currentTimeMillis())) {
                reply.accept(rule.getId() + " " + rule.getTarget() + " " + rule.getScope() + " expires=" + (rule.getExpiresAt() == 0 ? "permanent" : rule.getExpiresAt()) + " reason=" + rule.getReason());
                if (++displayed >= 20) break;
            }
            reply.accept(messages.text("ops.rules-shown", displayed)); return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (store.snapshot().stream().noneMatch(rule -> rule.getId().equals(args[2]) && rule.getEffect() == effect)) { reply.accept(messages.text("ops.rules-missing", name)); return true; }
            ConnectionGuard.getLookupRuntime().submit(() -> {
                try { return store.remove(args[2]); } catch (java.io.IOException failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); throw new IllegalStateException("Rule removal failed (details redacted)."); }
            }).thenAccept(removed -> reply.accept(removed ? messages.getString("ops.rule-removed") : messages.getString("ops.rule-absent")))
                    .exceptionally(error -> { reply.accept(messages.getString("ops.rule-remove-failed")); return null; }); return true;
        }
        if (args.length >= 6 && args[1].equalsIgnoreCase("add")) {
            final AccessRule.Scope scope; final long expiry; final String reason; final String target;
            try {
                int boundary = -1;
                for (int i = 3; i < args.length - 2; i++) {
                    if (args[i].matches("(?i)vpn|geo|all") && (args[i+1].equalsIgnoreCase("permanent") || args[i+1].matches("[1-9][0-9]{0,5}[smhd]"))) { boundary = i; break; }
                }
                if (boundary < 0) throw new IllegalArgumentException("Missing scope/duration.");
                scope = AccessRule.Scope.valueOf(args[boundary].toUpperCase(Locale.ROOT)); expiry = expiry(args[boundary+1]);
                String raw = String.join(" ", Arrays.copyOfRange(args, 2, boundary));
                target = raw.startsWith("\"") && raw.endsWith("\"") ? raw.substring(1, raw.length()-1) : raw;
                reason = String.join(" ", Arrays.copyOfRange(args, boundary+2, args.length));
                // Validate before scheduling any write.
                new AccessRule("validation", effect, scope, target, expiry, reason);
            } catch (IllegalArgumentException | ArithmeticException invalid) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(invalid, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); reply.accept(messages.getString("ops.rule-invalid")); return true; }
            ConnectionGuard.getLookupRuntime().submit(() -> {
                try { return store.add(effect, scope, target, expiry, reason); }
                catch (java.io.IOException failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); throw new IllegalStateException("Rule write failed (details redacted)."); }
            }).thenAccept(rule -> reply.accept(messages.text("ops.rule-stored", rule.getId(), rule.getEffect(), rule.getTarget(), rule.getScope())))
                    .exceptionally(error -> { reply.accept(messages.getString("ops.rule-write-failed")); return null; }); return true;
        }
        reply.accept(messages.text("ops.rule-usage", name));
        reply.accept(messages.text("ops.rule-usage-tail", name));
        return true;
    }
    static long expiry(String text) {
        if (text.equalsIgnoreCase("permanent")) return 0;
        if (!text.matches("[1-9][0-9]{0,5}[smhd]")) throw new IllegalArgumentException("Invalid duration.");
        long count = Long.parseLong(text.substring(0, text.length() - 1));
        char unit = text.charAt(text.length() - 1);
        long milliseconds = Math.multiplyExact(count, unit == 's' ? 1000L : unit == 'm' ? 60000L : unit == 'h' ? 3600000L : 86400000L);
        if (milliseconds > 31536000000L) throw new IllegalArgumentException("Duration exceeds one year.");
        return Math.addExact(System.currentTimeMillis(), milliseconds);
    }
}
