package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;
import java.util.function.Function;

/** Explicit selected IDs, voting contract and local budgets in the complete operator draft. */
public final class ExtensionSettings {
    public final List<Source> sources;
    public ExtensionSettings(Function<String, Object> value) {
        boolean enabled = GuardSettings.bool(value, "integrations.providers.enabled", false);
        Object raw = value.apply("integrations.providers.sources");
        List<Source> selected = new ArrayList<>(); Set<String> ids = new HashSet<>();
        if (raw != null) {
            if (!(raw instanceof List) || ((List<?>) raw).size() > 8) throw new IllegalArgumentException("integrations.providers.sources must be a list of at most eight sources.");
            for (Object item : (List<?>) raw) {
                if (!(item instanceof Map)) throw new IllegalArgumentException("Extension source must be an object.");
                Map<?, ?> source = (Map<?, ?>) item;
                if (!new HashSet<>(Arrays.asList("id", "voting", "daily-budget", "minute-budget")).containsAll(source.keySet())) throw new IllegalArgumentException("Unsupported extension source field.");
                String id = GuardSettings.string(source::get, "id", "");
                if (!id.matches("[a-z][a-z0-9-]{0,31}") || !ids.add(id)) throw new IllegalArgumentException("Invalid or duplicate selected extension ID (values redacted).");
                boolean voting = GuardSettings.bool(source::get, "voting", true);
                int day = GuardSettings.integer(source::get, "daily-budget", 0), minute = GuardSettings.integer(source::get, "minute-budget", 0);
                if (day < 0 || day > 1000000 || minute < 0 || minute > 1000000) throw new IllegalArgumentException("Extension budgets must be 0..1000000.");
                if (enabled) selected.add(new Source(id, voting, day, minute));
            }
        }
        if (enabled && selected.isEmpty()) throw new IllegalArgumentException("Enabled extensions require an explicit selected source.");
        sources = Collections.unmodifiableList(selected);
    }
    public int voting() { return (int) sources.stream().filter(source -> source.voting).count(); }
    public static final class Source {
        public final String id;
        public final boolean voting;
        public final int dayBudget, minuteBudget;
        private Source(String id, boolean voting, int day, int minute) { this.id = id; this.voting = voting; this.dayBudget = day; this.minuteBudget = minute; }
    }
}
