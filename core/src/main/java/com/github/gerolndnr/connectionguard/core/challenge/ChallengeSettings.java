package com.github.gerolndnr.connectionguard.core.challenge;

import java.util.Properties;

/** Strict optional native challenge configuration; invalid values never silently disable a gate. */
public final class ChallengeSettings {
    public final boolean enabled;
    public final int timeoutSeconds, maximumAttempts, maximumSessions;
    private ChallengeSettings(boolean enabled, int timeout, int attempts, int sessions) {
        this.enabled = enabled; this.timeoutSeconds = timeout; this.maximumAttempts = attempts; this.maximumSessions = sessions;
    }
    public static ChallengeSettings read(Properties values) {
        if (values == null) throw new IllegalArgumentException("Missing challenge configuration");
        for (Object key : values.keySet()) if (!"enabled".equals(key) && !"timeout-seconds".equals(key)
                && !"maximum-attempts".equals(key) && !"maximum-sessions".equals(key))
            throw new IllegalArgumentException("Unknown challenge configuration key");
        String enabled = values.getProperty("enabled", "false");
        if (!"true".equals(enabled) && !"false".equals(enabled)) throw new IllegalArgumentException("Invalid challenge enabled flag");
        return new ChallengeSettings("true".equals(enabled), number(values, "timeout-seconds", 30, 2, 120),
                number(values, "maximum-attempts", 3, 1, 8), number(values, "maximum-sessions", 64, 1, 4096));
    }
    private static int number(Properties values, String key, int fallback, int minimum, int maximum) {
        String raw = values.getProperty(key, Integer.toString(fallback));
        if (!raw.matches("[0-9]{1,4}")) throw new IllegalArgumentException("Invalid challenge bound");
        int result = Integer.parseInt(raw);
        if (result < minimum || result > maximum) throw new IllegalArgumentException("Challenge bound out of range");
        return result;
    }
}
