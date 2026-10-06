package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.vpn.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Pure in-memory membership. No network, file work or player-IP transmission at lookup. */
public final class IntelVpnProvider implements VpnProvider {
    private final String generation;
    private final IntelSettings.Relay relay;
    private final int maxAgeHours;
    private final transient IntelSnapshot snapshot;
    public IntelVpnProvider(IntelSnapshot snapshot){this.snapshot=snapshot;generation=snapshot.generation+":"+snapshot.asOf;relay=snapshot.settings.relay;maxAgeHours=snapshot.settings.maxAgeHours;}
    @Override public String sourceName(){return IntelSnapshot.ID;}
    @Override public boolean stableSourceId(){return true;}
    @Override public boolean isLocal(){return true;}
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip){return CompletableFuture.completedFuture(Optional.of(snapshot.vpn(ip,System.currentTimeMillis())));}
}
