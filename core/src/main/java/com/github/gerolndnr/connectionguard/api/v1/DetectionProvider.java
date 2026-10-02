package com.github.gerolndnr.connectionguard.api.v1;

import java.util.concurrent.CompletableFuture;

/** Called on bounded plugin workers with a normalized literal IP; never access platform world state here. */
@FunctionalInterface
public interface DetectionProvider {
    CompletableFuture<DetectionObservation> lookup(String literalAddress);
}
