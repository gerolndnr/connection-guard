package com.github.gerolndnr.connectionguard.api.v1;

/** Versioned extension entry point. Include as compile-only; never shade another copy into an addon. */
public final class ConnectionGuardApi {
    public static final int CONTRACT_VERSION = 1;
    private ConnectionGuardApi() { }
    public static ProviderRegistration registerProvider(ProviderDescriptor descriptor, DetectionProvider provider) {
        return com.github.gerolndnr.connectionguard.core.extensions.ExtensionRegistry.register(descriptor, provider);
    }
    public static ObserverRegistration registerDecisionObserver(String id, DecisionObserver observer) {
        return com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.register(id, observer);
    }
}
