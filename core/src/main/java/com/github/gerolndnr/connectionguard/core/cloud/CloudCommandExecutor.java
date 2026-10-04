package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;

/**
 * The only things the dashboard may ask this server to do. Anything else is refused here,
 * whatever the server sends: no console commands, no config keys, no files.
 */
final class CloudCommandExecutor {
    private CloudCommandExecutor() { }

    static String execute(String type, JsonObject c) throws IOException {
        switch (type) {
            case "access_rule.add": {
                AccessRuleStore store = store();
                AccessRule.Effect effect = AccessRule.Effect.valueOf(text(c, "effect").toUpperCase(Locale.ROOT));
                AccessRule.Scope scope = AccessRule.Scope.valueOf(text(c, "scope").toUpperCase(Locale.ROOT));
                String note = c.has("note") && !c.get("note").isJsonNull() ? c.get("note").getAsString() : null;
                String reason = "dashboard" + (note == null || note.isEmpty() ? "" : ": " + note.replaceAll("[\\r\\n]", " "));
                AccessRule rule = store.add(effect, scope, text(c, "target"), 0, reason.length() > 128 ? reason.substring(0, 128) : reason);
                return "Stored " + rule.getId();
            }
            case "access_rule.remove": {
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
                    String ip = Exemptions.normalize(c.get("ip").getAsString());
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

    private static String text(JsonObject c, String key) {
        if (!c.has(key) || c.get(key).isJsonNull()) throw new IllegalArgumentException("Missing " + key);
        String value = c.get(key).getAsString();
        if (value.length() > 64) throw new IllegalArgumentException("Value too long");
        return value;
    }
}
