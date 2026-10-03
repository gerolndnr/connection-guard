package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.commands.LookupInformation;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;
import com.github.gerolndnr.connectionguard.core.lookup.GeoLookup;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookupInformationTest {
    @Test void missingGeoAndUnknownVpnRemainExplicitlyUnknown() {
        VpnResult vpn = new VpnResult("192.0.2.1", false);
        vpn.setUnknown(FailureReason.TIMEOUT);
        GeoLookup geo = new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 10);
        LookupInformation view = LookupInformation.asOf(vpn, geo, 1000);
        assertEquals(ProviderVote.Status.UNKNOWN, view.getVpnStatus());
        assertEquals("UNKNOWN", view.getCountry());
        assertEquals("UNKNOWN", view.getCity());
        assertEquals("UNKNOWN", view.getIsp());
        assertFalse(geo.getResult().isPresent());
        assertEquals(FailureReason.TIMEOUT, vpn.getSourceReason());
    }
    @Test void expiredGeoAndVpnAreNotPresentedAsCurrentFacts() {
        VpnResult vpn = new VpnResult("192.0.2.2", true);
        vpn.setValidUntil(1000);
        GeoResult result = new GeoResult("192.0.2.2", "DE", "Example", "Fixture ISP");
        result.setValidUntil(1000);
        LookupInformation view = LookupInformation.asOf(vpn, new GeoLookup(Optional.of(result), FailureReason.NONE, true, 0), 1000);
        assertEquals(ProviderVote.Status.UNKNOWN, view.getVpnStatus());
        assertEquals("UNKNOWN", view.getCountry());
        assertEquals("Fixture ISP", result.getIspName());
    }
    @Test void freshFactsAreCopiedAndMissingIndividualFieldsStayUnknown() {
        VpnResult vpn = new VpnResult("192.0.2.3", false);
        GeoResult result = new GeoResult("192.0.2.3", "DE", "", "Fixture ISP");
        LookupInformation view = LookupInformation.asOf(vpn, new GeoLookup(Optional.of(result), FailureReason.NONE, false, 1), 1000);
        assertEquals(ProviderVote.Status.NEGATIVE, view.getVpnStatus());
        assertEquals("DE", view.getCountry());
        assertEquals("UNKNOWN", view.getCity());
        assertEquals("Fixture ISP", view.getIsp());
    }
}
