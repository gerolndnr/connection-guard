package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;

/** A rejected reload (invalid config, rules or messages; busy workers) is the operator's to fix: they get the
 * reason, and only unexpected failures become error reports. */
public final class ReloadFailures {
    private ReloadFailures() { }
    public static void record(Throwable failure) {
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException || failure instanceof java.io.IOException) return;
        PluginErrorReports.record(failure, PluginErrorReports.Context.RELOAD);
    }
    /** Our validators name the setting and redact values. Subtypes such as NumberFormatException can carry the
     * raw input, so they get no reason line. */
    public static String reason(Throwable rejected, MessageCatalog messages) {
        if (rejected == null || rejected.getClass() != IllegalArgumentException.class && rejected.getClass() != IllegalStateException.class
                && !(rejected instanceof MessageCatalog.InvalidMessage)) return null;
        String text = rejected.getMessage();
        if (text == null || text.isEmpty() || text.length() > 300 || text.indexOf('§') >= 0 || text.chars().anyMatch(Character::isISOControl)) return null;
        return messages.text("command.reload-reason", text);
    }
}
