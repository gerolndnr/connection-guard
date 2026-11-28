package com.github.gerolndnr.connectionguard.core.account;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;
import java.util.function.Function;

/**
 * Account Intelligence: checks a joined account against public ban lists and only informs staff. It never denies,
 * kicks or delays a login. Off unless a provider is enabled explicitly; a missing section keeps it off.
 */
public final class AccountSettings {
    /** Any ban in force, also one without a category. */
    public static final String ANY = "*";
    public static final List<String> DEFAULT_CATEGORIES = Collections.unmodifiableList(Arrays.asList("cheating", "exploit", "evasion"));
    public final boolean mizan;
    public final boolean notifyStaff;
    public final Set<String> categories;
    public final int minuteBudget;
    public final int cacheMinutes;
    private final String mizanKey;

    public AccountSettings(Function<String, Object> value) {
        mizan = GuardSettings.bool(value, "provider.account.mizan.enabled", false);
        mizanKey = GuardSettings.string(value, "provider.account.mizan.api-key", "").trim();
        if (mizanKey.length() > 256 || mizanKey.chars().anyMatch(c -> c <= ' ' || c > '~'))
            throw new IllegalArgumentException("provider.account.mizan.api-key is invalid (value redacted).");
        // Mizan allows 120 uncached keyless requests per minute per address and 300 per organisation with a key.
        minuteBudget = GuardSettings.integer(value, "provider.account.mizan.minute-budget", mizanKey.isEmpty() ? 100 : 250);
        if (minuteBudget < 1 || minuteBudget > 300) throw new IllegalArgumentException("provider.account.mizan.minute-budget must be 1..300.");
        notifyStaff = GuardSettings.bool(value, "behavior.account.notify-staff", true);
        cacheMinutes = GuardSettings.integer(value, "provider.cache.expiration.account", 60);
        if (cacheMinutes < 1 || cacheMinutes > 10080) throw new IllegalArgumentException("provider.cache.expiration.account must be 1..10080 minutes.");
        Object raw = value.apply("behavior.account.notify-categories");
        Set<String> selected = new LinkedHashSet<>();
        if (raw == null) selected.addAll(DEFAULT_CATEGORIES);
        else {
            if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size() > 64)
                throw new IllegalArgumentException("behavior.account.notify-categories must be a list of 1..64 category keys.");
            for (Object item : (List<?>) raw) {
                if (!(item instanceof String) || !(ANY.equals(item) || ((String) item).matches("[a-z][a-z0-9_-]{0,31}")))
                    throw new IllegalArgumentException("behavior.account.notify-categories takes lowercase category keys or '*' (values redacted).");
                selected.add((String) item);
            }
        }
        categories = Collections.unmodifiableSet(selected);
    }

    public boolean enabled() { return mizan; }
    String mizanKey() { return mizanKey; }
    boolean keyed() { return !mizanKey.isEmpty(); }

    /** A record is worth a warning when a ban in force has one of the selected categories. */
    public boolean warns(AccountRecord record) {
        if (record == null || record.bansInForce <= 0) return false;
        if (categories.contains(ANY)) return true;
        for (String category : record.categoriesInForce) if (categories.contains(category)) return true;
        return false;
    }

    /** Equal settings keep the running queue, cache and counters across a reload. */
    String signature() { return mizan + "\n" + mizanKey + "\n" + minuteBudget + "\n" + cacheMinutes; }
}
