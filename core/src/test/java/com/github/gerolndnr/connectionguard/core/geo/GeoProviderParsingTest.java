package com.github.gerolndnr.connectionguard.core.geo;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeoProviderParsingTest {
    @Test void ipApiChecksFailureBeforeReadingLocationFields() {
        assertFalse(IpApiGeoProvider.parse("192.0.2.1", JsonParser.parseString("{\"status\":\"fail\",\"message\":\"reserved range\"}").getAsJsonObject()).isPresent());
    }
    @Test void ipApiReadsSuccessfulLocation() {
        GeoResult result = IpApiGeoProvider.parse("192.0.2.1", JsonParser.parseString("{\"status\":\"success\",\"countryCode\":\"ZZ\",\"city\":\"Test City\",\"isp\":\"ISP $1\"}").getAsJsonObject()).get();
        assertEquals("ISP $1", result.getIspName());
    }
    @Test void proxyCheckDenialNeedsNoLocationFields() {
        assertFalse(ProxyCheckGeoProvider.parse("192.0.2.1", JsonParser.parseString("{\"status\":\"denied\"}").getAsJsonObject()).isPresent());
    }
    @Test void proxyCheckV3UsesLocationIsocodeAndCityAndReadsAsn() {
        GeoResult result = ProxyCheckGeoProvider.parseV3("192.0.2.1", JsonParser.parseString("{\"status\":\"ok\",\"192.0.2.1\":{\"location\":{\"isocode\":\"US\",\"city\":\"Ashburn\"},\"network\":{\"asn\":\"AS16509\",\"provider\":\"Fixture ISP\"}}}").getAsJsonObject()).get();
        assertEquals("US", result.getCountryName()); assertEquals("Ashburn", result.getCityName()); assertEquals(Long.valueOf(16509), result.getAsn());
        assertFalse(ProxyCheckGeoProvider.parseV3("192.0.2.1", JsonParser.parseString("{\"status\":\"ok\",\"192.0.2.1\":{\"location\":{\"isocode\":null}}}").getAsJsonObject()).isPresent());
    }
}
