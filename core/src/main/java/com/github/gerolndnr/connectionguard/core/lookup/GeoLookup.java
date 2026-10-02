package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import java.util.Optional;

public final class GeoLookup {
    private final Optional<GeoResult> result;
    private final FailureReason reason;
    private final boolean cached;
    private final long durationMillis;
    public GeoLookup(Optional<GeoResult> result, FailureReason reason, boolean cached, long durationMillis) {
        this.result = result; this.reason = reason; this.cached = cached; this.durationMillis = durationMillis;
    }
    public Optional<GeoResult> getResult() { return result; }
    public FailureReason getReason() { return reason; }
    public boolean isCached() { return cached; }
    public long getDurationMillis() { return durationMillis; }
}
