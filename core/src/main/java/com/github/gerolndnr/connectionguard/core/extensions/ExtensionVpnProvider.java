package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Captured adapter. Only its public descriptor is serialized into a detection namespace. */
public final class ExtensionVpnProvider implements VpnProvider {
    private final String id, version, configurationFingerprint;
    private final boolean voting;
    private final transient ExtensionRegistry.Entry registration;
    public ExtensionVpnProvider(ExtensionSettings.Source source) {
        id = source.id; voting = source.voting; registration = ExtensionRegistry.find(id);
        if (registration != null && voting != registration.descriptor.isVoting()) throw new IllegalArgumentException("Selected voting mode disagrees with the registered extension contract.");
        version = registration == null ? "missing" : registration.descriptor.getVersion();
        configurationFingerprint = registration == null ? "missing" : registration.descriptor.getConfigurationFingerprint();
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String address) {
        final String ip = Exemptions.normalize(address);
        if (!isAvailable()) return CompletableFuture.completedFuture(Optional.of(unavailable(ip)));
        CompletableFuture<DetectionObservation> future = registration.callback.lookup(ip);
        if (future == null) throw new LookupException(FailureReason.INVALID_RESPONSE);
        return future.thenApply(observation -> {
            if (!isAvailable()) return Optional.of(unavailable(ip));
            if (observation == null) throw new LookupException(FailureReason.INVALID_RESPONSE);
            FailureReason reason = FailureReason.valueOf(observation.getReason().name());
            if (reason != FailureReason.NONE && reason != FailureReason.NO_EVIDENCE && reason != FailureReason.STALE_DATA && reason != FailureReason.NO_PROVIDER)
                throw new LookupException(reason);
            VpnResult result = new VpnResult(ip, observation.getStatus() == DetectionObservation.Status.POSITIVE,
                    Optional.ofNullable(observation.getMetadata().getOperator()));
            DetectionMetadata metadata = observation.getMetadata();
            Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
            metadata.getTypes().forEach((type, flag) -> types.put(DetectionDetails.Type.valueOf(type.name()), flag));
            result.setDetails(new DetectionDetails(types, metadata.getAsn(), metadata.getIsp(), metadata.getOperator(),
                    metadata.getCountry(), metadata.getRisk(), metadata.getConfidence()));
            result.setStatus(ProviderVote.Status.valueOf(observation.getStatus().name()));
            if (observation.getStatus() == DetectionObservation.Status.UNKNOWN) result.setUnknown(reason);
            result.setValidUntil(observation.getValidUntil()); result.setSourceVersion(observation.getSourceVersion());
            return Optional.of(result);
        });
    }
    private static VpnResult unavailable(String ip) { VpnResult result = new VpnResult(ip, false); result.setUnknown(FailureReason.NO_PROVIDER); return result; }
    @Override public String sourceName() { return "extension." + id; }
    @Override public boolean stableSourceId() { return true; }
    @Override public boolean isVoting() { return voting; }
    @Override public boolean isAvailable() { return registration != null && registration.isRegistered(); }
    public String describe() { return sourceName() + " registered=" + isAvailable() + " voting=" + voting + " api=1 version=" + version + " configuration=" + configurationFingerprint; }
}
