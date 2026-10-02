package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.vpn.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class LocalVpnProvider implements VpnProvider {
    private final String sourceId, generation;
    private final transient LocalSnapshot snapshot;
    public LocalVpnProvider(LocalSnapshot snapshot) {
        this.snapshot = snapshot; sourceId = "local." + snapshot.source.id;
        generation = snapshot.source.fingerprint() + ":" + snapshot.version + ":" + snapshot.dataTime;
    }
    @Override public String sourceName() { return sourceId; }
    @Override public boolean isVoting() { return snapshot.source.isVoting(); }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        return CompletableFuture.completedFuture(Optional.of(snapshot.vpn(ip, System.currentTimeMillis())));
    }
}
