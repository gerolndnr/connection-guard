package com.github.gerolndnr.connectionguard.core.vpn;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface VpnProvider {
    CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress);
    /** Optional stable, public ID. It must never contain endpoints or credentials. */
    default String sourceName() { return null; }
    /** Enrichment sources do not vote for or against the generic VPN threshold. */
    default boolean isVoting() { return true; }
}
