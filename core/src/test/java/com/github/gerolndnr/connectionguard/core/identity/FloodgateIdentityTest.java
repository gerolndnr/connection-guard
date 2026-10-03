package com.github.gerolndnr.connectionguard.core.identity;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FloodgateIdentityTest {
    private final UUID javaId = UUID.fromString("dddddddd-1111-4222-8333-444444444444");
    private final UUID bedrockId = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private final InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 21001);
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private static final class Record { UUID id; String name; InetSocketAddress address; Record(UUID id, String name, InetSocketAddress address) { this.id=id;this.name=name;this.address=address; } }
    private static final class Api implements FloodgateIdentity.NativeApi {
        Record player; Collection<?> active; RuntimeException failure; boolean currentApi=true; int reportedCount=1;
        Api(Record player) { this.player=player; active=Collections.singletonList(player); }
        public Object find(UUID uuid) { if (failure != null) throw failure; return player; }
        public Collection<?> active() { return active; }
        public boolean isCurrentApi() { return currentApi; }
        public int count() { return reportedCount; }
        public UUID id(Object player) { return ((Record)player).id; }
        public String name(Object player) { return ((Record)player).name; }
        public InetSocketAddress address(Object player) { return ((Record)player).address; }
    }
    private FloodgateIdentity.Probe probe(Api api, UUID id) { return FloodgateIdentity.capture(api,id,".CGNative",remote,connected::get); }
    @Test void activeNativeRecordMustMatchCanonicalIdNameAndFullSocket() {
        Api api=new Api(new Record(javaId,".CGNative",remote));
        FloodgateIdentity.Probe proof=probe(api,javaId); assertTrue(proof.isBound());assertTrue(proof.isCurrent());
        api.player.name=".Other";assertFalse(proof.isCurrent());
    }
    @Test void linkedCanonicalJavaIdentityIsNotItsUnlinkedAlias() {
        Api api=new Api(new Record(javaId,".CGNative",remote));
        assertTrue(probe(api,javaId).isBound());assertFalse(probe(api,bedrockId).isBound());
    }
    @Test void pendingRemovalRecordIsNotAnActiveSession() {
        Api api=new Api(new Record(javaId,".CGNative",remote));api.active=Collections.emptyList();
        FloodgateIdentity.Probe proof=probe(api,javaId);assertTrue(proof.isClaimed());assertFalse(proof.isBound());
    }
    @Test void sameIpAndUuidOnAnotherPortCannotReuseTheNativeRecord() {
        Api api=new Api(new Record(javaId,".CGNative",new InetSocketAddress("127.0.0.1",21002)));
        assertFalse(probe(api,javaId).isBound());
    }
    @Test void anotherAddressCannotReuseTheNativeRecord() {
        Api api=new Api(new Record(javaId,".CGNative",new InetSocketAddress("192.0.2.81",21001)));
        assertFalse(probe(api,javaId).isBound());
    }
    @Test void nativeReplacementInvalidatesTheCapturedSessionEvenWithEqualValues() {
        Api api=new Api(new Record(javaId,".CGNative",remote));FloodgateIdentity.Probe proof=probe(api,javaId);
        api.player=new Record(javaId,".CGNative",remote);api.active=Collections.singletonList(api.player);
        assertFalse(proof.isCurrent());assertTrue(probe(api,javaId).isCurrent());
    }
    @Test void disconnectOrRemovalInvalidatesPreviouslyBoundProof() {
        Api api=new Api(new Record(javaId,".CGNative",remote));FloodgateIdentity.Probe proof=probe(api,javaId);
        connected.set(false);assertFalse(proof.isCurrent());connected.set(true);
        api.active=Collections.emptyList();assertFalse(proof.isCurrent());
    }
    @Test void replacingTheCanonicalApiInvalidatesItsOldRecord() {
        Api api=new Api(new Record(javaId,".CGNative",remote));FloodgateIdentity.Probe proof=probe(api,javaId);
        api.currentApi=false;assertFalse(proof.isCurrent());assertFalse(probe(api,javaId).isBound());
    }
    @Test void oversizedNativeCollectionsCannotBecomeAnUnboundedAuthorityScan() {
        Api api=new Api(new Record(javaId,".CGNative",remote));api.reportedCount=65537;
        assertFalse(probe(api,javaId).isBound());api.reportedCount=1;
        api.active=Collections.nCopies(65537,api.player);assertFalse(probe(api,javaId).isBound());
    }
    @Test void missingRecordOrBrokenNativeApiCannotInventAProof() {
        Api api=new Api(null);assertFalse(probe(api,javaId).isBound());
        api.failure=new IllegalStateException("synthetic private error");FloodgateIdentity.Probe proof=probe(api,javaId);
        assertTrue(proof.isClaimed());assertFalse(proof.isCurrent());
    }
    @Test void missingIdentityNameOrResolvedSocketCannotInventAProof() {
        Api api=new Api(new Record(javaId,".CGNative",remote));
        assertFalse(FloodgateIdentity.capture(api,null,".CGNative",remote,connected::get).isBound());
        assertFalse(FloodgateIdentity.capture(api,javaId,null,remote,connected::get).isBound());
        assertFalse(FloodgateIdentity.capture(api,javaId,".CGNative",null,connected::get).isBound());
        assertFalse(FloodgateIdentity.capture(api,javaId,".CGNative",InetSocketAddress.createUnresolved("example.invalid",21001),connected::get).isBound());
    }
    @Test void noNativeSdkOnTheUnitClasspathIsAnOptionalAbsence() {
        assertFalse(FloodgateIdentity.isAvailable());assertFalse(FloodgateIdentity.capture(javaId,".CGNative",remote,connected::get).isBound());
    }
}
