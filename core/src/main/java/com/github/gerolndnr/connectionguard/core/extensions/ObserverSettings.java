package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;
import java.util.function.Function;

/** Independent explicit observer selection; never changes provider facts or their cache namespace. */
public final class ObserverSettings {
    public final List<String> ids;
    public ObserverSettings(Function<String, Object> value) {
        boolean enabled = GuardSettings.bool(value, "integrations.observers.enabled", false);
        Object raw = value.apply("integrations.observers.ids");
        List<String> selected = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (raw != null) {
            if (!(raw instanceof List) || ((List<?>) raw).size() > 8)
                throw new IllegalArgumentException("integrations.observers.ids must be a list of at most eight IDs.");
            for (Object item : (List<?>) raw) {
                if (!(item instanceof String) || !((String) item).matches("[a-z][a-z0-9-]{0,31}") || !seen.add((String) item))
                    throw new IllegalArgumentException("Invalid or duplicate observer ID (values redacted).");
                if (enabled) selected.add((String) item);
            }
        }
        if (enabled && selected.isEmpty()) throw new IllegalArgumentException("Enabled observers require explicit selected IDs.");
        ids = Collections.unmodifiableList(selected);
    }
}
