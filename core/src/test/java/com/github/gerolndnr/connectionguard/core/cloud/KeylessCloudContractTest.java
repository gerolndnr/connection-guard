package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeylessCloudContractTest {
    @Test void dashboardCanExplicitlyToggleAllThreeRecipientsAndAbsentSelectionsSnapshotAsOff() {
        for (String id : new String[]{"blackbox", "ipcheck", "zowi"}) {
            String path = "provider.vpn."+id+".enabled";
            JsonObject values = new JsonObject(); values.addProperty(path, true);
            assertEquals(Boolean.TRUE, CloudManagedConfig.parse(values).get(path));
            values.addProperty(path, "true"); assertThrows(IllegalArgumentException.class, () -> CloudManagedConfig.parse(values));
            assertFalse(CloudManagedConfig.snapshot(k -> null).get(path).getAsBoolean());
            assertEquals("vpn-"+id, CloudRecorder.sourceId("vpn."+id));
            assertEquals("vpn-"+id, CloudRecorder.sourceId(id));
        }
    }
    @Test void broadBlackboxEvidenceTravelsWithoutMislabelledClassificationFlagsOrNewEnums() throws Exception {
        Source source = new Source("blackbox", Scope.VPN, new DetectionObservation(DetectionObservation.Status.POSITIVE, DetectionObservation.Reason.NONE,
                new DetectionMetadata(Collections.emptyMap(), null, null, null, null, null, null), 0, null), 1, true, false);
        DecisionObservation observation = new DecisionObservation(Platform.VELOCITY, Phase.LOGIN, Mode.ENFORCE, IdentityTrust.UNTRUSTED, null, "192.0.2.1",
                Outcome.DENY, Reason.VPN_FLAG, Check.POSITIVE, Check.UNKNOWN, System.currentTimeMillis(), 1, false, Collections.singleton(Flag.VPN), Collections.singletonList(source), Collections.emptyList());
        CloudRecorder recorder = new CloudRecorder(); recorder.acceptEvents(true); recorder.onDecision(observation);
        JsonObject event = recorder.drain(10, System.currentTimeMillis()).events.get(0);
        assertEquals("VPN_FLAG", event.get("reason").getAsString());
        assertFalse(event.has("reason_detail"), "The existing strict event schema must not receive unagreed fields.");
        assertFalse(event.getAsJsonArray("sources").get(0).getAsJsonObject().has("reason_detail"));
        assertEquals("vpn-blackbox", event.getAsJsonArray("sources").get(0).getAsJsonObject().get("id").getAsString());
        recorder.acceptEvents(false); recorder.onDecision(observation); assertEquals(0, recorder.drain(10, System.currentTimeMillis()).events.size());
    }
}
