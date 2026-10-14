package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class IpQueryVpnProviderTest {
    private static JsonObject response(boolean vpn, boolean proxy, boolean tor, String asn) {
        // Synthetic API-shaped response: documentation IP, no live provider request.
        return JsonParser.parseString("{\"ip\":\"192.0.2.1\","
                + "\"isp\":{\"asn\":" + asn + ",\"isp\":\"Unknown\"},"
                + "\"location\":{\"country_code\":\"DE\"},"
                + "\"risk\":{\"is_vpn\":" + vpn + ",\"is_proxy\":" + proxy
                + ",\"is_tor\":" + tor + ",\"is_datacenter\":false,\"risk_score\":50}}")
                .getAsJsonObject();
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,true,false", "false,false,true", "false,false,false"})
    void reservedZeroDoesNotDiscardPositiveOrNegativeSecurityVerdicts(boolean vpn, boolean proxy, boolean tor) {
        for (String asn : new String[]{"\"AS0\"", "0"}) {
            VpnResult result = IpQueryVpnProvider.parse("192.0.2.1", response(vpn, proxy, tor, asn)).get();
            assertEquals(vpn || proxy || tor, result.isVpn());
            assertEquals(vpn || proxy || tor ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE, result.getStatus());
            assertNull(result.getDetails().getAsn());
            assertEquals(Boolean.valueOf(vpn), result.getDetails().get(DetectionDetails.Type.VPN));
            assertEquals(Boolean.valueOf(proxy), result.getDetails().get(DetectionDetails.Type.PROXY));
            assertEquals(Boolean.valueOf(tor), result.getDetails().get(DetectionDetails.Type.TOR));
            assertEquals("Unknown", result.getDetails().getIsp());
            assertEquals("DE", result.getDetails().getCountry());
            assertEquals(Integer.valueOf(50), result.getDetails().getRisk());
        }
    }

    @Test
    void missingSecurityFlagsRemainUnknownEvenWithReservedZero() {
        JsonObject json = response(false, false, false, "\"AS0\"");
        json.getAsJsonObject("risk").remove("is_vpn");
        assertFalse(IpQueryVpnProvider.parse("192.0.2.1", json).isPresent());
    }

    @Test
    void malformedAsnStillInvalidatesTheProviderAnswer() {
        assertThrows(IllegalArgumentException.class,
                () -> IpQueryVpnProvider.parse("192.0.2.1", response(true, false, false, "\"ASabc\"")));
    }
}
