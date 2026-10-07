package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntelCloudContractTest {
    private JsonObject event(DetectionMetadata metadata,DetectionObservation.Status status){
        Source source=new Source("connectionguard-intel",Scope.VPN,new DetectionObservation(status,DetectionObservation.Reason.NONE,metadata,0,null),0,true,false);
        DecisionObservation o=new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.UNTRUSTED,null,"192.0.2.1",status==DetectionObservation.Status.POSITIVE?Outcome.DENY:Outcome.ALLOW,Reason.VPN_FLAG,status==DetectionObservation.Status.POSITIVE?Check.POSITIVE:Check.NEGATIVE,Check.UNKNOWN,System.currentTimeMillis(),0,false,Collections.emptySet(),Collections.singletonList(source),Collections.emptyList());
        return CloudRecorder.event(o).getAsJsonArray("sources").get(0).getAsJsonObject();
    }
    @Test void managedSwitchesAreStrictAndAbsentOldFilesStayOptIn(){
        String enabled="provider.local.connectionguard-intel.enabled",relay="provider.local.connectionguard-intel.relay";JsonObject values=new JsonObject();values.addProperty(enabled,true);values.addProperty(relay,"VPN");
        assertEquals(Boolean.TRUE,CloudManagedConfig.parse(values).get(enabled));assertEquals("VPN",CloudManagedConfig.parse(values).get(relay));
        assertFalse(CloudManagedConfig.snapshot(k->null).get(enabled).getAsBoolean());assertEquals("ALLOW",CloudManagedConfig.snapshot(k->null).get(relay).getAsString());
        values.addProperty(relay,"PROXY");assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(values));
        values.addProperty(relay,"ALLOW");values.addProperty(enabled,"true");assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(values));
        assertEquals("connectionguard-intel",CloudRecorder.sourceId("connectionguard-intel"));
    }
    @Test void exactOptionalFieldsCarryOnlyObservedTrueTypesAndManifestTime(){
        Map<DetectionMetadata.Type,Boolean> types=new EnumMap<>(DetectionMetadata.Type.class);types.put(DetectionMetadata.Type.VPN,true);types.put(DetectionMetadata.Type.HOSTING,true);types.put(DetectionMetadata.Type.PROXY,false);
        long asOf=1791283200000L;JsonObject source=event(new DetectionMetadata(types,null,null,null,null,null,null).withDataAsOf(asOf),DetectionObservation.Status.POSITIVE);
        assertEquals("connectionguard-intel",source.get("id").getAsString());assertEquals(asOf,source.get("data_as_of").getAsLong());assertEquals("[\"VPN\",\"HOSTING\"]",source.get("types").toString());
        assertFalse(source.has("reason_detail"));assertFalse(source.has("as_of"));
        JsonObject absent=event(DetectionMetadata.empty(),DetectionObservation.Status.NEGATIVE);assertFalse(absent.has("types"));assertFalse(absent.has("data_as_of"));
    }
    @Test void allowedRelayKeepsItsCategoryWithoutInventingVpn(){
        JsonObject source=event(new DetectionMetadata(Collections.singletonMap(DetectionMetadata.Type.RELAY,true),null,null,null,null,null,null).withDataAsOf(1791283200000L),DetectionObservation.Status.NEGATIVE);
        assertEquals("NEGATIVE",source.get("status").getAsString());assertEquals("[\"RELAY\"]",source.get("types").toString());
    }
    @Test void additiveMetadataCopiesPreserveExactRiskAndRejectBadDates(){
        DetectionMetadata m=DetectionMetadata.withExactRisk(Collections.singletonMap(DetectionMetadata.Type.VPN,true),null,null,null,null,new java.math.BigDecimal("1.25"),null).withDataAsOf(1791283200000L);
        assertEquals(new java.math.BigDecimal("1.25"),m.getExactRisk());assertEquals(1791283200000L,m.getDataAsOf().longValue());
        assertThrows(IllegalArgumentException.class,()->m.withDataAsOf(0L));assertThrows(IllegalArgumentException.class,()->m.withDataAsOf(-1L));
    }
    @Test void proxyUsesExistingCloudTypeAndSignedPublicationTime() {
        JsonObject source=event(new DetectionMetadata(Collections.singletonMap(DetectionMetadata.Type.PROXY,true),null,null,null,null,null,null).withDataAsOf(1791283200000L),DetectionObservation.Status.POSITIVE);
        assertEquals("[\"PROXY\"]",source.get("types").toString());assertEquals(1791283200000L,source.get("data_as_of").getAsLong());
    }
    @Test void unconfirmedBlackboxDecisionHasHumanReasonWithoutExtendingStrictCloudFields() {
        Source source=new Source("blackbox",Scope.VPN,DetectionObservation.unknown(DetectionObservation.Reason.NO_EVIDENCE),1,true,false);
        assertEquals("Blackbox listed the address, not confirmed",source.getReasonDescription());
        DecisionObservation o=new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.UNTRUSTED,null,"192.0.2.1",Outcome.ALLOW,Reason.CHECKS_COMPLETE,Check.NEGATIVE,Check.UNKNOWN,System.currentTimeMillis(),1,false,Collections.emptySet(),Collections.singletonList(source),Collections.emptyList());
        JsonObject wire=CloudRecorder.event(o).getAsJsonArray("sources").get(0).getAsJsonObject();
        assertEquals("vpn-blackbox",wire.get("id").getAsString());assertEquals("UNKNOWN",wire.get("status").getAsString());assertEquals("NO_EVIDENCE",wire.get("reason").getAsString());assertFalse(wire.has("reason_detail"));
        assertEquals("INVALID_RESPONSE",new Source("blackbox",Scope.VPN,DetectionObservation.unknown(DetectionObservation.Reason.INVALID_RESPONSE),1,true,false).getReasonDescription());
    }

}
