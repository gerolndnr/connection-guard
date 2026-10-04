package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import java.util.Locale;

/**
 * The only things the dashboard may ask this server to do. Anything else is refused here,
 * whatever the server sends: no console commands, no config keys, no files.
 */
final class CloudCommandExecutor {
    private CloudCommandExecutor() { }

    static String execute(String type, JsonObject c) throws IOException {
        if (c == null) throw new IllegalArgumentException("Missing command.");
        String[] fields;
        switch (type) {
            case "access_rule.add": fields = new String[]{"id", "type", "effect", "scope", "target", "note", "expires_at"}; break;
            case "access_rule.remove": fields = new String[]{"id", "type", "effect", "target"}; break;
            case "cache.clear": fields = new String[]{"id", "type", "ip"}; break;
            case "unlink": fields = new String[]{"id", "type"}; break;
            default: throw new IllegalArgumentException("Unsupported command.");
        }
        Set<String> allowed = new HashSet<>(Arrays.asList(fields));
        if (!allowed.containsAll(c.keySet()) || !type.equals(text(c, "type"))
                || !text(c, "id").matches("cmd_[A-Za-z0-9]{12,32}"))
            throw new IllegalArgumentException("Invalid command envelope (values redacted).");
        switch (type) {
            case "access_rule.add": {
                long deadline = expiry(c);
                if (deadline == -1) return "Already expired";
                AccessRuleStore store = store();
                AccessRule.Effect effect = AccessRule.Effect.valueOf(text(c, "effect").toUpperCase(Locale.ROOT));
                AccessRule.Scope scope = AccessRule.Scope.valueOf(text(c, "scope").toUpperCase(Locale.ROOT));
                String note = c.has("note") && !c.get("note").isJsonNull() ? note(c) : null;
                String reason = "dashboard" + (note == null || note.isEmpty() ? "" : ": " + note.replaceAll("[\\r\\n]", " "));
                AccessRule rule = store.add(effect, scope, text(c, "target"), deadline, reason.length() > 128 ? reason.substring(0, 128) : reason);
                return "Stored " + rule.getId();
            }
            case "access_rule.remove": {
                long deadline = expiry(c);
                if (deadline == -1) return "Already expired";
                AccessRuleStore store = store();
                AccessRule.Effect effect = AccessRule.Effect.valueOf(text(c, "effect").toUpperCase(Locale.ROOT));
                String target = text(c, "target");
                int removed = 0;
                for (AccessRule rule : store.snapshot()) if (rule.getEffect() == effect && rule.getTarget().equalsIgnoreCase(target) && store.remove(rule.getId())) removed++;
                return removed == 0 ? "No matching rule" : "Removed " + removed;
            }
            case "cache.clear": {
                if (ConnectionGuard.getCacheProvider() == null) throw new IllegalStateException("Cache unavailable");
                if (c.has("ip") && !c.get("ip").isJsonNull()) {
                    String ip = Exemptions.normalize(text(c, "ip", 45));
                    ConnectionGuard.getCacheProvider().removeVpnResult(ip);
                    ConnectionGuard.getCacheProvider().removeGeoResult(ip);
                    return "Cleared cache entry";
                }
                ConnectionGuard.getCacheProvider().removeAllVpnResults();
                ConnectionGuard.getCacheProvider().removeAllGeoResults();
                return "Cleared cache";
            }
            case "unlink":
                return "Unlinked";
            default:
                throw new IllegalArgumentException("Unsupported command");
        }
    }

    private static AccessRuleStore store() {
        AccessRuleStore store = ConnectionGuard.getRuleStore();
        if (store == null) throw new IllegalStateException("Rule store unavailable");
        return store;
    }

    /** Absolute UTC milliseconds: never coerce, round, overflow or turn a deadline into permanence. */
    private static long expiry(JsonObject c) {
        JsonElement e = c.get("expires_at");
        if (e == null || e.isJsonNull()) return 0; // Legacy explicitly permanent operator rule.
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid rule deadline (values redacted).");
        final long at;
        try { at = new BigDecimal(e.getAsString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid rule deadline (values redacted).");
        }
        // The shared JavaScript protocol uses safe integer epoch milliseconds.
        if (at <= 0 || at > 9_007_199_254_740_991L)
            throw new IllegalArgumentException("Expired or unsupported rule deadline (values redacted).");
        return at <= System.currentTimeMillis() ? -1 : at;
    }

    private static String note(JsonObject c) {
        JsonElement e = c.get("note");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length() > 128)
            throw new IllegalArgumentException("Command note is invalid (values redacted).");
        return e.getAsString();
    }

    private static String text(JsonObject c, String key) { return text(c, key, 64); }
    private static String text(JsonObject c, String key, int max) {
        JsonElement e = c.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Command text is invalid (values redacted).");
        String value = e.getAsString();
        if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("Command text is invalid (values redacted).");
        return value;
    }
}
