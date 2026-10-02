package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.geo.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class LocalGeoProvider implements GeoProvider {
    private final String generation;
    private final transient LocalSnapshot snapshot;
    private final transient LocalSnapshot asn;
    public LocalGeoProvider(LocalSnapshot snapshot, LocalSnapshot asn) { this.snapshot = snapshot; this.asn = asn;
        generation = snapshot.source.fingerprint() + ":" + snapshot.version + ":" + snapshot.dataTime
                + (asn == null ? "" : ":" + asn.source.fingerprint() + ":" + asn.version + ":" + asn.dataTime); }
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) {
        FailureReason reason = snapshot.readiness(System.currentTimeMillis());
        if (reason != FailureReason.NONE) throw new LookupException(reason);
        Optional<GeoResult> result = snapshot.geo(ip, System.currentTimeMillis());
        if (!result.isPresent()) throw new LookupException(FailureReason.NO_EVIDENCE);
        if (asn != null && asn.readiness(System.currentTimeMillis()) == FailureReason.NONE) {
            DetectionDetails details = asn.vpn(ip, System.currentTimeMillis()).getDetails();
            GeoResult enriched = new GeoResult(ip, result.get().getCountryName(), result.get().getCityName(), details.getIsp() == null ? "Unknown" : details.getIsp());
            enriched.setAsn(details.getAsn()); enriched.setValidUntil(Math.min(snapshot.validUntil(), asn.validUntil()));
            enriched.setSourceVersion(LocalSource.hash(generation.getBytes(java.nio.charset.StandardCharsets.UTF_8))); result = Optional.of(enriched);
        }
        return CompletableFuture.completedFuture(result);
    }
}
