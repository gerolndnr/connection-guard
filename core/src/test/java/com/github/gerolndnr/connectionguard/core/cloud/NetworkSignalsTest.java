package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.api.v1.AdmissionObservation;
import com.github.gerolndnr.connectionguard.api.v1.AdmissionResponse;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSignalsTest {
    private static DecisionObservation login(String ip, UUID uuid, Outcome outcome, Check vpn, List<Rule> rules, List<AdmissionObservation> bans) {
        return new DecisionObservation(Platform.VELOCITY, Phase.LOGIN, Mode.ENFORCE, IdentityTrust.AUTHENTICATED, uuid, ip, outcome,
                outcome == Outcome.DENY ? Reason.VPN_FLAG : Reason.CHECKS_COMPLETE, vpn, Check.KNOWN, System.currentTimeMillis(), 5, false,
                EnumSet.noneOf(Flag.class), Collections.emptyList(), rules, bans);
    }
    private static DecisionObservation login(String ip, UUID uuid) {
        return login(ip, uuid, Outcome.ALLOW, Check.NEGATIVE, Collections.emptyList(), Collections.emptyList());
    }

    @Test void prefixesAreSlash24AndSlash48AndNeverLocalAddresses() {
        assertEquals("203.0.113.0/24", NetworkSignals.prefix("203.0.113.77"));
        assertEquals("198.51.100.0/24", NetworkSignals.prefix("::ffff:198.51.100.9"));
        assertEquals("2001:db8:1::/48", NetworkSignals.prefix("2001:db8:1:ab::5"));
        assertEquals("2001:db8:0::/48", NetworkSignals.prefix("2001:db8::1"));
        for (String local : new String[] {"127.0.0.1", "10.1.2.3", "192.168.1.5", "100.64.0.1", "fd00::1", "::1", "not-an-ip", null})
            assertNull(NetworkSignals.prefix(local), String.valueOf(local));
    }

    @Test void countsDistinctAccountsOutcomesBansAndAllowancesPerPrefixWithoutIdentifiers() {
        NetworkSignals s = new NetworkSignals();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.record(login("203.0.113.5", a));
        s.record(login("203.0.113.9", a));
        s.record(login("203.0.113.200", b, Outcome.DENY, Check.POSITIVE, Collections.emptyList(), Collections.emptyList()));
        s.record(login("203.0.113.7", b, Outcome.DENY, Check.NOT_CHECKED, Collections.emptyList(),
                Collections.singletonList(new AdmissionObservation("libertybans", AdmissionResponse.activeBan(0), 3))));
        s.record(login("198.51.100.1", null, Outcome.ALLOW, Check.EXEMPT,
                Collections.singletonList(new Rule("r1", Scope.VPN, Effect.ALLOW, Match.MATCH, true)), Collections.emptyList()));
        s.record(login("10.0.0.1", a));
        JsonObject window = s.drain();
        JsonArray prefixes = window.getAsJsonArray("prefixes");
        assertEquals(2, prefixes.size());
        JsonObject doc = prefixes.get(1).getAsJsonObject();
        assertEquals("203.0.113.0/24", doc.get("p").getAsString());
        assertEquals(4, doc.get("logins").getAsLong());
        assertEquals(2, doc.get("accounts").getAsLong());
        assertEquals(1, doc.get("vpn").getAsLong());
        assertEquals(2, doc.get("denied").getAsLong());
        assertEquals(1, doc.get("banned").getAsLong());
        JsonObject allowed = prefixes.get(0).getAsJsonObject();
        assertEquals(1, allowed.get("allowed_by_rule").getAsLong());
        assertEquals(0, allowed.get("accounts").getAsLong());
        String text = window.toString();
        assertFalse(text.contains(a.toString()) || text.contains(b.toString()) || text.contains("203.0.113.5") || text.contains("r1"));
        assertEquals(0, s.drain().getAsJsonArray("prefixes").size(), "a drained window starts empty");
    }

    @Test void boundedPrefixesAndRestoreAddsAWindowBack() {
        NetworkSignals s = new NetworkSignals();
        for (int i = 0; i < NetworkSignals.MAX_PREFIXES + 10; i++) s.record(login("198." + (i / 256) + "." + (i % 256) + ".1", UUID.randomUUID()));
        JsonObject first = s.drain();
        assertEquals(NetworkSignals.MAX_PREFIXES, first.getAsJsonArray("prefixes").size());
        assertEquals(10, first.get("overflow").getAsLong());
        s.record(login("203.0.113.1", UUID.randomUUID()));
        s.restore(first);
        JsonObject again = s.drain();
        assertEquals(first.get("overflow").getAsLong() + 1, again.get("overflow").getAsLong(), "the restored window exceeds the bound by one prefix");
        long logins = 0; for (com.google.gson.JsonElement e : again.getAsJsonArray("prefixes")) logins += e.getAsJsonObject().get("logins").getAsLong();
        assertEquals(NetworkSignals.MAX_PREFIXES, again.getAsJsonArray("prefixes").size());
        assertTrue(logins >= NetworkSignals.MAX_PREFIXES);
    }
}
