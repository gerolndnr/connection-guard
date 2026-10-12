package com.github.gerolndnr.connectionguard.core.config;

import java.util.Locale;
import java.util.function.Function;

/** Local operator choice; never enables or changes a selected source or enforcement mode. */
public final class ApiLookupPolicy {
    public enum Mode { CONFIGURED, LOCAL_ONLY }
    public final Mode mode;
    public final int dailyBudget;
    public ApiLookupPolicy(Mode mode, int dailyBudget) {
        if (mode == null || dailyBudget < 0) throw new IllegalArgumentException("Invalid API lookup policy (values redacted).");
        this.mode = mode; this.dailyBudget = dailyBudget;
    }
    public static ApiLookupPolicy read(Function<String,Object> values) {
        Mode mode;
        try { mode = Mode.valueOf(GuardSettings.string(values,"lookup.api-policy","CONFIGURED").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("lookup.api-policy must be CONFIGURED or LOCAL_ONLY."); }
        return new ApiLookupPolicy(mode, GuardSettings.integer(values,"lookup.api-daily-budget",0));
    }
    public static ApiLookupPolicy defaults() { return new ApiLookupPolicy(Mode.CONFIGURED,0); }
    public boolean allowsExternal() { return mode == Mode.CONFIGURED; }
}
