package com.github.gerolndnr.connectionguard.core.rules;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.cache.CacheCodec;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.api.v1.DetectionMetadata;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class ExactRiskTest {
    private DetectionDetails details(String risk) { return DetectionDetails.withExactRisk(null, null, null, null, null, risk == null ? null : new BigDecimal(risk), null); }
    private ProviderVote vote(String risk) { return new ProviderVote("source", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 1, details(risk)); }
    private EvidencePolicy.Decision evaluate(String selector, ProviderVote... sources) {
        AccessRule rule = new AccessRule("fixture", AccessRule.Effect.DENY, AccessRule.Scope.VPN, selector, 0, "Synthetic risk boundary");
        return EvidencePolicy.evaluate(Collections.singletonList(rule), "192.0.2.1", null, false, AccessRule.Scope.VPN, Arrays.asList(sources), System.currentTimeMillis());
    }
    @Test void fractionalThresholdIsExactAndSourceSpecificWithoutConfidenceInference() {
        assertFalse(evaluate("risk:source:80", vote("79.9999999999999999999999999999")).isDenied());
        assertTrue(evaluate("risk:source:80", vote("80.0000000000000000000000000001")).isDenied());
        assertTrue(evaluate("risk:source:0", vote("0")).isDenied());
        assertTrue(evaluate("risk:source:80", vote(null)).isUnresolved());
        assertTrue(evaluate("risk:other:80", vote("99.9")).isUnresolved());
        assertTrue(evaluate("confidence:source:80", vote("99.9")).isUnresolved());
    }
    private VpnResult result(DetectionDetails details) {
        VpnResult result = new VpnResult("192.0.2.1", false); result.setDetails(details);
        result.setVotes(Collections.singletonList(new ProviderVote("source", ProviderVote.Status.NEGATIVE, FailureReason.NONE, 1, details)));
        result.setCachedOn(System.currentTimeMillis()); return result;
    }
    @Test void newDecimalCacheAndOldIntegerCachePreserveFactsInTopLevelAndVotes() {
        String encoded = CacheCodec.encode(result(details("79.999")));
        VpnResult cached = CacheCodec.vpn(encoded, "192.0.2.1", 60000).get();
        assertEquals(new BigDecimal("79.999"), cached.getDetails().getExactRisk()); assertNull(cached.getDetails().getRisk());
        assertFalse(evaluate("risk:source:80", cached.getVotes().get(0)).isDenied());
        DetectionDetails legacy = new DetectionDetails(null, null, null, null, null, 80, null);
        String old = CacheCodec.encode(result(legacy)); assertFalse(old.contains("exactRisk"));
        cached = CacheCodec.vpn(old, "192.0.2.1", 60000).get();
        assertEquals(new BigDecimal("80"), cached.getDetails().getExactRisk()); assertEquals(Integer.valueOf(80), cached.getDetails().getRisk());
        assertTrue(evaluate("risk:source:80", cached.getVotes().get(0)).isDenied());
    }
    @ParameterizedTest @ValueSource(strings={"\"79.999\"", "101", "-1", "1e-1001", "1e1001", "true", "{}"})
    void malformedDecimalCacheCannotManufactureAValidRisk(String value) {
        String encoded = CacheCodec.encode(result(details("79.999")));
        assertFalse(CacheCodec.vpn(encoded.replace("\"exactRisk\":79.999", "\"exactRisk\":" + value), "192.0.2.1", 60000).isPresent());
    }
    @Test void longestSupportedApiPrecisionAndSmallExponentRoundTripButHugeLegacyExponentIsRejected() {
        for (String risk : new String[]{"79." + String.join("", Collections.nCopies(126, "9")), "1e-1000"}) {
            DetectionDetails supplied=details(risk);
            String encoded=CacheCodec.encode(result(supplied));
            VpnResult cached=CacheCodec.vpn(encoded,"192.0.2.1",60000).get();
            assertEquals(0,supplied.getExactRisk().compareTo(cached.getDetails().getExactRisk()));
        }
        assertThrows(IllegalArgumentException.class,()->details("79."+String.join("",Collections.nCopies(127,"9"))));
        String encoded=CacheCodec.encode(result(new DetectionDetails(null,null,null,null,null,80,50)));
        assertFalse(CacheCodec.vpn(encoded.replace("\"confidence\":50","\"confidence\":1e1000000"),"192.0.2.1",60000).isPresent());
    }
    @Test void incompatibleLegacyAndExactCacheFieldsAreRejectedRatherThanRounded() {
        String encoded = CacheCodec.encode(result(details("80")));
        assertFalse(CacheCodec.vpn(encoded.replace("\"risk\":80", "\"risk\":79"), "192.0.2.1", 60000).isPresent());
        encoded = CacheCodec.encode(result(details("79.999")));
        assertFalse(CacheCodec.vpn(encoded.replace("\"exactRisk\":79.999", "\"risk\":79,\"exactRisk\":79.999"), "192.0.2.1", 60000).isPresent());
    }
    @Test void additivePublicMetadataKeepsOriginalIntegerContractAndExactDecimalAccess() {
        DetectionMetadata metadata = DetectionMetadata.withExactRisk(null, null, null, null, null, new BigDecimal("79.999"), null);
        assertEquals(new BigDecimal("79.999"), metadata.getExactRisk()); assertNull(metadata.getRisk());
        assertEquals(new BigDecimal("80"), new DetectionMetadata(null, null, null, null, null, 80, null).getExactRisk());
        assertEquals(Integer.valueOf(80), DetectionMetadata.withExactRisk(null, null, null, null, null, new BigDecimal("80.000"), null).getRisk());
        assertThrows(IllegalArgumentException.class, () -> details("101")); assertThrows(IllegalArgumentException.class, () -> details("-0.0001"));
        assertThrows(IllegalArgumentException.class, () -> details("1e-1001"));
        assertThrows(IllegalArgumentException.class, () -> DetectionMetadata.withExactRisk(null, null, null, null, null, new BigDecimal("101"), null));
    }
}
