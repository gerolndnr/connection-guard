package com.github.gerolndnr.connectionguard.core.identity;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionIdentityTest {
    private final UUID id=UUID.fromString("dddddddd-1111-4222-8333-444444444444");
    private final InetSocketAddress address=new InetSocketAddress("127.0.0.1",21001);
    private ConnectionIdentity identity(UUID uuid, boolean authenticated, boolean legacy, boolean declared) {
        return ConnectionIdentity.resolve(uuid,"CGIdentity",address,()->true,authenticated ? AuthenticatedIdentity.capture(uuid,"CGIdentity",address,
                () -> new AuthenticatedIdentity.State(uuid,"CGIdentity",address,true,true)) : AuthenticatedIdentity.unavailable(),legacy,declared,false);
    }
    @Test void offlineClaimedUuidIsNeitherTrustedNorVerified() {
        ConnectionIdentity value=identity(id,false,false,false);assertFalse(value.isTrusted());assertFalse(value.isVerified());
        assertEquals(DecisionObservation.IdentityTrust.UNTRUSTED,value.observationTrust());
    }
    @Test void declaredForwardingPreservesLegacyExemptionsWithoutVerifiedAuthority() {
        ConnectionIdentity value=identity(id,false,false,true);assertTrue(value.isTrusted());assertFalse(value.isVerified());
        assertEquals(ConnectionIdentity.Source.DECLARED_FORWARDING,value.source());assertEquals(DecisionObservation.IdentityTrust.FORWARDED,value.observationTrust());
    }
    @Test void globalServerOnlineFlagIsASeparateLegacyProvenance() {
        ConnectionIdentity value=identity(id,false,true,false);assertTrue(value.isTrusted());assertFalse(value.isVerified());
        assertEquals(ConnectionIdentity.Source.LEGACY_SERVER_ONLINE,value.source());
    }
    @Test void perConnectionAuthenticationRequiresTheSameLiveConnection() {
        AtomicBoolean live=new AtomicBoolean(true);
        ConnectionIdentity value=ConnectionIdentity.resolve(id,"CGIdentity",address,live::get,
                AuthenticatedIdentity.capture(id,"CGIdentity",address,()->new AuthenticatedIdentity.State(id,"CGIdentity",address,true,live.get())),false,true,false);
        assertEquals(ConnectionIdentity.Source.AUTHENTICATED_CONNECTION,value.source());assertTrue(value.isVerified());
        live.set(false);assertFalse(value.isVerified());
    }
    @Test void missingUuidOverridesEveryTrustFlag() {
        ConnectionIdentity value=identity(null,true,true,true);assertFalse(value.isTrusted());assertFalse(value.isVerified());
        assertEquals(ConnectionIdentity.Source.UNTRUSTED,value.source());
    }
    @Test void aNativeProofHasItsOwnSourceAndCannotOutliveItsRecord() {
        AtomicBoolean live=new AtomicBoolean(true);ConnectionIdentity value=ConnectionIdentity.nativeConnection(id,live::get);
        assertEquals(DecisionObservation.IdentityTrust.FLOODGATE,value.observationTrust());assertTrue(value.isVerified());
        live.set(false);assertFalse(value.isVerified());assertTrue(value.requiresCurrentProof());
    }
    @Test void nativeProofFailuresBecomeUnverifiedWithoutExceptionDetails() {
        ConnectionIdentity value=ConnectionIdentity.nativeConnection(id,()->{throw new IllegalStateException("synthetic private error");});
        assertFalse(value.isCurrent());assertFalse(value.isVerified());
    }
    @Test void authenticatedModeCannotBeReplacedByAnOfflineConnectionWhileStillLive() {
        AtomicBoolean authenticated=new AtomicBoolean(true);
        AuthenticatedIdentity.Probe proof=AuthenticatedIdentity.capture(id,"CGIdentity",address,
                ()->new AuthenticatedIdentity.State(id,"CGIdentity",address,authenticated.get(),true));
        ConnectionIdentity value=ConnectionIdentity.resolve(id,"CGIdentity",address,()->true,proof,false,false,false);
        assertTrue(value.isVerified()); authenticated.set(false);
        assertFalse(value.isVerified()); assertFalse(value.isCurrent()); assertTrue(value.requiresCurrentProof());
    }
    @Test void anotherConnectionProofCannotVerifyAnUnrelatedUuidOrNameOrSocket() {
        AuthenticatedIdentity.Probe proof=AuthenticatedIdentity.capture(id,"CGIdentity",address,
                ()->new AuthenticatedIdentity.State(id,"CGIdentity",address,true,true));
        assertFalse(ConnectionIdentity.resolve(UUID.randomUUID(),"CGIdentity",address,()->true,proof,false,false,false).isVerified());
        assertFalse(ConnectionIdentity.resolve(id,"CGOther",address,()->true,proof,false,false,false).isVerified());
        assertFalse(ConnectionIdentity.resolve(id,"CGIdentity",new InetSocketAddress("127.0.0.1",21002),()->true,proof,false,false,false).isVerified());
    }
    @Test void missingNativeSdkCannotUpgradeADeclaredIdentity() {
        ConnectionIdentity value=ConnectionIdentity.resolve(id,"CGIdentity",address,()->true,AuthenticatedIdentity.unavailable(),false,true,true);
        assertTrue(value.isTrusted());assertFalse(value.isVerified());assertEquals(ConnectionIdentity.Source.DECLARED_FORWARDING,value.source());
    }
}
