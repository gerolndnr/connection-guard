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
        if (args.length == 0 || !NAMES.contains(args[0].toLowerCase(Locale.ROOT))) return false;
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!permission.test("connectionguard.command." + name)) { reply.accept("You do not have permission for this command."); return true; }
        AccessRuleStore store = ConnectionGuard.getRuleStore();
        if (store == null) { reply.accept("Rule store unavailable; check server startup logs."); return true; }
        AccessRule.Effect effect = name.equals("allow") ? AccessRule.Effect.ALLOW : name.equals("deny") ? AccessRule.Effect.DENY : AccessRule.Effect.EXEMPT;
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            int displayed = 0;
            for (AccessRule rule : store.snapshot()) if (rule.getEffect() == effect && (rule.getExpiresAt() == 0 || rule.getExpiresAt() > System.currentTimeMillis())) {
                reply.accept(rule.getId() + " " + rule.getTarget() + " " + rule.getScope() + " expires=" + (rule.getExpiresAt() == 0 ? "permanent" : rule.getExpiresAt()) + " reason=" + rule.getReason());
                if (++displayed >= 20) break;
            }
            reply.accept("Displayed " + displayed + " active rules (maximum 20); complete file: access-rules.json."); return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (store.snapshot().stream().noneMatch(rule -> rule.getId().equals(args[2]) && rule.getEffect() == effect)) { reply.accept("No matching " + name + " rule."); return true; }
            ConnectionGuard.getLookupRuntime().submit(() -> {
                try { return store.remove(args[2]); } catch (java.io.IOException failure) { throw new IllegalStateException("Rule removal failed (details redacted)."); }
            }).thenAccept(removed -> reply.accept(removed ? "Rule removed." : "Rule was already absent."))
                    .exceptionally(error -> { reply.accept("Rule removal failed; existing rules preserved."); return null; }); return true;
        }
        if (args.length >= 6 && args[1].equalsIgnoreCase("add")) {
            final AccessRule.Scope scope; final long expiry; final String reason;
            try {
                scope = AccessRule.Scope.valueOf(args[3].toUpperCase(Locale.ROOT)); expiry = expiry(args[4]);
                reason = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
                // Validate before scheduling any write.
                new AccessRule("validation", effect, scope, args[2], expiry, reason);
            } catch (IllegalArgumentException | ArithmeticException invalid) { reply.accept("Invalid target, scope, duration or reason. Use literal IP/CIDR or canonical UUID; no player names."); return true; }
            ConnectionGuard.getLookupRuntime().submit(() -> {
                try { return store.add(effect, scope, args[2], expiry, reason); }
                catch (java.io.IOException failure) { throw new IllegalStateException("Rule write failed (details redacted)."); }
            }).thenAccept(rule -> reply.accept("Stored " + rule.getId() + " " + rule.getEffect() + " " + rule.getTarget() + " " + rule.getScope()))
                    .exceptionally(error -> { reply.accept("Rule write failed; active rules preserved."); return null; }); return true;
        }
        reply.accept("/cg " + name + " add <IP/CIDR/UUID> <vpn|geo|all> <15m|2h|7d|permanent> <reason>");
        reply.accept("/cg " + name + " list | remove <rule-id>; explicit deny overrides allow/exempt; UUID requires trusted identity.");
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
