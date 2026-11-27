package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudEventNameTest {
    private static DecisionObservation decision() {
        return new DecisionObservation(Platform.VELOCITY, Phase.PRE_AUTHENTICATION, Mode.ENFORCE, IdentityTrust.UNTRUSTED, null,
                "192.0.2.7", Outcome.DENY, Reason.ACCESS_RULE, Check.NOT_CHECKED, Check.NOT_CHECKED, 1000, 2, false,
                EnumSet.of(Flag.ACCESS_POLICY), Collections.emptyList(), Collections.emptyList());
    }
    @Test void reportedNameIsSentNextToAMissingUuid() {
        JsonObject e = CloudRecorder.event(decision(), "Bedrock Player");
        assertEquals("Bedrock Player", e.get("name").getAsString()); assertTrue(e.get("uuid").isJsonNull());
    }
    @Test void noReportedNameMeansNoField() {
        assertFalse(CloudRecorder.event(decision()).has("name"));
        assertFalse(CloudRecorder.event(decision(), "  \u0000‮ ").has("name"));
    }
    @Test void namesAreCleanedAndBounded() {
        assertEquals("Fixture_1", CloudRecorder.name(" Fixture_1\n"));
        assertEquals("evil", CloudRecorder.name("e‮v\u0007il"));
        assertEquals(32, CloudRecorder.name(String.join("", Collections.nCopies(40, "x"))).length());
        assertEquals(".Gamer😀", CloudRecorder.name(".Gamer😀"));
        assertEquals(32, CloudRecorder.name(String.join("", Collections.nCopies(20, "\uD83D\uDE00"))).length(), "cut at 32 chars, never mid-pair");
        assertNull(CloudRecorder.name(null));
    }
}
