package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.GeoLookup;
import com.github.gerolndnr.connectionguard.core.lookup.LookupFreshness;
import com.github.gerolndnr.connectionguard.core.lookup.ProviderVote;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

/** An immutable display snapshot. Missing facts never become synthetic GeoResults or negative votes. */
public final class LookupInformation {
    private final ProviderVote.Status vpnStatus;
    private final String country, city, isp;
    private LookupInformation(ProviderVote.Status vpnStatus, GeoResult geo) {
        this.vpnStatus = vpnStatus;
        country = geo == null ? "UNKNOWN" : field(geo.getCountryName());
        city = geo == null ? "UNKNOWN" : field(geo.getCityName());
        isp = geo == null ? "UNKNOWN" : field(geo.getIspName());
    }
    private static String field(String text) { return text == null || text.isEmpty() ? "UNKNOWN" : text; }
    public static LookupInformation asOf(VpnResult vpn, GeoLookup geo, long now) {
        return new LookupInformation(LookupFreshness.vpn(vpn, now).getStatus(), LookupFreshness.geo(geo, now).getResult().orElse(null));
    }
    public ProviderVote.Status getVpnStatus() { return vpnStatus; }
    public String getCountry() { return country; }
    public String getCity() { return city; }
    public String getIsp() { return isp; }
}
