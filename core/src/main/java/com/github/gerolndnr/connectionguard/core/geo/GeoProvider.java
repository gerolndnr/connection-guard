package com.github.gerolndnr.connectionguard.core.geo;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface GeoProvider {
    CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress);
}
