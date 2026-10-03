package com.github.gerolndnr.connectionguard.core.identity;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedIdentityTest {
    final UUID uuid=UUID.fromString("dddddddd-1111-4222-8333-444444444444");
    final InetSocketAddress socket=new InetSocketAddress("127.0.0.1",21001);
    AuthenticatedIdentity.State state(UUID id,String name,InetSocketAddress address,boolean auth,boolean connected) {
        return new AuthenticatedIdentity.State(id,name,address,auth,connected);
    }
    @Test void authenticatedLiveCanonicalConnectionBinds() {
        AuthenticatedIdentity.Probe proof=AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,true,true));
        assertTrue(proof.isBound());assertTrue(proof.isCurrent());
    }
    @Test void absentUuidNeverBindsAnAuthenticatedConnection() {
        assertFalse(AuthenticatedIdentity.capture(null,"CGIdentity",socket,()->state(null,"CGIdentity",socket,true,true)).isBound());
    }
    @Test void initiallyOfflineOrDisconnectedConnectionsCannotBind() {
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,false,true)).isBound());
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,true,false)).isBound());
    }
    @Test void unboundUuidAndNameCannotBorrowAnotherCanonicalConnection() {
        assertFalse(AuthenticatedIdentity.capture(UUID.randomUUID(),"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,true,true)).isBound());
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGOther",socket,()->state(uuid,"CGIdentity",socket,true,true)).isBound());
    }
    @Test void socketIncludesPortAndCannotBorrowAnotherConnectionOnSameIp() {
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",new InetSocketAddress("127.0.0.1",21002),()->state(uuid,"CGIdentity",socket,true,true)).isBound());
    }
    @Test void unusableAddressAndNameCannotEstablishAuthority() {
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",InetSocketAddress.createUnresolved("fixture.invalid",21001),()->state(uuid,"CGIdentity",socket,true,true)).isBound());
        assertFalse(AuthenticatedIdentity.capture(uuid,"",socket,()->state(uuid,"",socket,true,true)).isBound());
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",null,()->state(uuid,"CGIdentity",null,true,true)).isBound());
    }
    @Test void changesAfterCaptureInvalidateCanonicalUuidNameSocketAndLiveness() {
        AtomicReference<AuthenticatedIdentity.State> current=new AtomicReference<>(state(uuid,"CGIdentity",socket,true,true));
        AuthenticatedIdentity.Probe proof=AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,current::get);
        assertTrue(proof.isCurrent());
        for(AuthenticatedIdentity.State changed:Arrays.asList(state(UUID.randomUUID(),"CGIdentity",socket,true,true),
                state(uuid,"CGOther",socket,true,true),state(uuid,"CGIdentity",new InetSocketAddress("127.0.0.1",21002),true,true),
                state(uuid,"CGIdentity",socket,false,true),state(uuid,"CGIdentity",socket,true,false))) {
            current.set(changed);assertFalse(proof.isCurrent());
        }
    }
    @Test void unavailableOrFailingPlatformReadsNeverInventAuthority() {
        assertFalse(AuthenticatedIdentity.unavailable().isCurrent());
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,null).isBound());
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,()->null).isBound());
        assertFalse(AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,()->{throw new IllegalStateException("synthetic private failure");}).isBound());
        AtomicReference<AuthenticatedIdentity.State> current=new AtomicReference<>(state(uuid,"CGIdentity",socket,true,true));
        AuthenticatedIdentity.Probe proof=AuthenticatedIdentity.capture(uuid,"CGIdentity",socket,()->{
            if(current.get()==null)throw new NoClassDefFoundError("synthetic private failure");return current.get();});
        current.set(null);assertFalse(proof.isCurrent());
    }
}
