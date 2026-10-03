package com.github.gerolndnr.connectionguard.core.identity;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardedIdentityTest {
    final UUID uuid=UUID.fromString("dddddddd-1111-4222-8333-444444444444");
    final InetSocketAddress socket=new InetSocketAddress("127.0.0.1",21001);
    ForwardedIdentity.State state(UUID id,String name,InetSocketAddress address,boolean auth,boolean connected) {
        return new ForwardedIdentity.State(id,name,address,auth,connected);
    }
    @Test void verifiedGatewayLiveCanonicalConnectionBinds() {
        ForwardedIdentity.Probe proof=ForwardedIdentity.capture(uuid,"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,true,true));
        assertTrue(proof.isBound());assertTrue(proof.isCurrent());
    }
    @Test void absentUuidNeverBindsAGatewayAssertion() {
        assertFalse(ForwardedIdentity.capture(null,"CGIdentity",socket,()->state(null,"CGIdentity",socket,true,true)).isBound());
    }
    @Test void unverifiedOrDisconnectedConnectionsCannotBind() {
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,false,true)).isBound());
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,true,false)).isBound());
    }
    @Test void unboundUuidAndNameCannotBorrowAnotherGatewayAssertion() {
        assertFalse(ForwardedIdentity.capture(UUID.randomUUID(),"CGIdentity",socket,()->state(uuid,"CGIdentity",socket,true,true)).isBound());
        assertFalse(ForwardedIdentity.capture(uuid,"CGOther",socket,()->state(uuid,"CGIdentity",socket,true,true)).isBound());
    }
    @Test void socketIncludesPortAndCannotBorrowAnotherConnectionOnSameIp() {
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",new InetSocketAddress("127.0.0.1",21002),()->state(uuid,"CGIdentity",socket,true,true)).isBound());
    }
    @Test void unusableAddressAndNameCannotEstablishAuthority() {
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",InetSocketAddress.createUnresolved("fixture.invalid",21001),()->state(uuid,"CGIdentity",socket,true,true)).isBound());
        assertFalse(ForwardedIdentity.capture(uuid,"",socket,()->state(uuid,"",socket,true,true)).isBound());
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",null,()->state(uuid,"CGIdentity",null,true,true)).isBound());
    }
    @Test void changesAfterCaptureInvalidateCanonicalUuidNameSocketAndLiveness() {
        AtomicReference<ForwardedIdentity.State> current=new AtomicReference<>(state(uuid,"CGIdentity",socket,true,true));
        ForwardedIdentity.Probe proof=ForwardedIdentity.capture(uuid,"CGIdentity",socket,current::get);
        assertTrue(proof.isCurrent());
        for(ForwardedIdentity.State changed:Arrays.asList(state(UUID.randomUUID(),"CGIdentity",socket,true,true),
                state(uuid,"CGOther",socket,true,true),state(uuid,"CGIdentity",new InetSocketAddress("127.0.0.1",21002),true,true),
                state(uuid,"CGIdentity",socket,false,true),state(uuid,"CGIdentity",socket,true,false))) {
            current.set(changed);assertFalse(proof.isCurrent());
        }
    }
    @Test void unavailableOrFailingPlatformReadsNeverInventAuthority() {
        assertFalse(ForwardedIdentity.unavailable().isCurrent());
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",socket,null).isBound());
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",socket,()->null).isBound());
        assertFalse(ForwardedIdentity.capture(uuid,"CGIdentity",socket,()->{throw new IllegalStateException("synthetic private failure");}).isBound());
        AtomicReference<ForwardedIdentity.State> current=new AtomicReference<>(state(uuid,"CGIdentity",socket,true,true));
        ForwardedIdentity.Probe proof=ForwardedIdentity.capture(uuid,"CGIdentity",socket,()->{
            if(current.get()==null)throw new NoClassDefFoundError("synthetic private failure");return current.get();});
        current.set(null);assertFalse(proof.isCurrent());
    }
}
