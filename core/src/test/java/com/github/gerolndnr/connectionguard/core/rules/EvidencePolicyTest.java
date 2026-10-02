package com.github.gerolndnr.connectionguard.core.rules;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule.*;
import com.github.gerolndnr.connectionguard.core.cache.CacheCodec;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidencePolicyTest {
    @TempDir Path directory;
    private AccessRule rule(Effect effect, String target) { return new AccessRule("fixture", effect, Scope.VPN, target, 0, "Synthetic case"); }
    private ProviderVote source(String id, Long asn, Integer risk, Boolean tor) {
        Map<DetectionDetails.Type, Boolean> types = new EnumMap<>(DetectionDetails.Type.class);
        if (tor != null) types.put(DetectionDetails.Type.TOR, tor);
        return new ProviderVote(id, ProviderVote.Status.NEGATIVE, FailureReason.NONE, 20,
                new DetectionDetails(types, asn, "Fixture ISP", "Fixture Operator", "DE", risk, null));
    }
    private EvidencePolicy.Decision evaluate(AccessRule rule, ProviderVote... sources) {
        return EvidencePolicy.evaluate(Collections.singletonList(rule), "192.0.2.1", null, false, Scope.VPN, Arrays.asList(sources), 100);
    }
    @Test void explicitTorRuleCanDenyIndependentlyOfLegacyBooleanAndUnavailableSecondSource() {
        EvidencePolicy.Decision result = evaluate(rule(Effect.DENY, "type:TOR"), source("a", 15169L, null, true),
                new ProviderVote("b", ProviderVote.Status.UNKNOWN, FailureReason.TIMEOUT, 50));
        assertTrue(result.isDenied()); assertFalse(result.isUnresolved());
    }
    @Test void conflictingSourcesCannotGrantButPositiveDenyStillApplies() {
        ProviderVote a = source("a", 15169L, null, false), b = source("b", 16509L, null, false);
        EvidencePolicy.Decision allowed = evaluate(rule(Effect.ALLOW, "asn:15169"), a, b);
        assertFalse(allowed.isBypassed()); assertTrue(allowed.isUnresolved());
        assertEquals(EvidencePolicy.Match.CONFLICT, allowed.getTrace().get(0).getMatch());
        assertTrue(evaluate(rule(Effect.DENY, "asn:15169"), a, b).isDenied());
    }
    @Test void incompleteSourcesCannotGrantAnAsnExemption() {
        assertFalse(evaluate(rule(Effect.EXEMPT, "asn:15169"), source("a", 15169L, null, null), source("b", null, null, null)).isBypassed());
        assertTrue(evaluate(rule(Effect.EXEMPT, "asn:15169"), source("a", 15169L, null, null)).isBypassed());
    }
    @Test void aMetadataGrantCannotOverrideAnUnresolvedHigherPriorityDeny() {
        List<AccessRule> rules = Arrays.asList(new AccessRule("allow", Effect.ALLOW, Scope.VPN, "asn:15169", 0, "Allow"), rule(Effect.DENY, "risk:a:80"));
        EvidencePolicy.Decision decision = EvidencePolicy.evaluate(rules, "192.0.2.1", null, false, Scope.VPN,
                Collections.singletonList(source("a", 15169L, null, false)), 100);
        assertFalse(decision.isBypassed()); assertTrue(decision.isUnresolved());
    }
    @Test void scoreIsSourceSpecificAndMissingScoreIsUnknownRatherThanZero() {
        AccessRule rule = rule(Effect.DENY, "risk:a:80");
        assertFalse(evaluate(rule, source("a", null, 20, null), source("b", null, 100, null)).isDenied());
        EvidencePolicy.Decision missing = evaluate(rule, source("a", null, null, null));
        assertTrue(missing.isUnresolved()); assertEquals(EvidencePolicy.Match.UNKNOWN, missing.getTrace().get(0).getMatch());
        assertTrue(evaluate(rule, source("a", null, 80, null)).isDenied());
        assertTrue(evaluate(rule(Effect.DENY, "confidence:a:80"), source("a", null, 100, null)).isUnresolved());
    }
    @Test void explicitAddressOverridePrecedesMetadataAndIdentityOverrideRequiresTrust() {
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        AccessRule allow = new AccessRule("identity", Effect.ALLOW, Scope.VPN, uuid.toString(), 0, "Known fixture");
        List<AccessRule> rules = Arrays.asList(rule(Effect.DENY, "type:TOR"), allow);
        List<ProviderVote> sources = Collections.singletonList(source("a", null, null, true));
        assertTrue(EvidencePolicy.evaluate(rules, "192.0.2.1", uuid, true, Scope.VPN, sources, 100).isBypassed());
        assertTrue(EvidencePolicy.evaluate(rules, "192.0.2.1", uuid, false, Scope.VPN, sources, 100).isDenied());
        rules = Arrays.asList(allow, new AccessRule("manual", Effect.DENY, Scope.VPN, "192.0.2.0/24", 0, "Manual deny"));
        assertTrue(EvidencePolicy.evaluate(rules, "192.0.2.1", uuid, true, Scope.VPN, sources, 100).isDenied());
    }
    @Test void scopeExpiryAndMetadataPrecedenceAreConsistent() {
        AccessRule expired = new AccessRule("expired", Effect.DENY, Scope.GEO, "country:DE", 99, "Expired");
        assertFalse(EvidencePolicy.evaluate(Collections.singletonList(expired), "192.0.2.1", null, false, Scope.GEO,
                Collections.singletonList(source("a", null, null, false)), 100).isDenied());
        assertFalse(EvidencePolicy.evaluate(Collections.singletonList(rule(Effect.DENY, "country:DE")), "192.0.2.1", null, false, Scope.GEO,
                Collections.singletonList(source("a", null, null, false)), 100).isDenied());
        List<AccessRule> rules = Arrays.asList(new AccessRule("allow", Effect.ALLOW, Scope.VPN, "asn:15169", 0, "Allow"), rule(Effect.DENY, "type:TOR"));
        assertTrue(EvidencePolicy.evaluate(rules, "192.0.2.1", null, false, Scope.VPN, Collections.singletonList(source("a", 15169L, null, true)), 100).isDenied());
    }
    @Test void literalIspOperatorCountrySelectorsAreExactAndCaseHandlingIsDocumented() {
        ProviderVote source = source("a", null, null, false);
        assertTrue(evaluate(rule(Effect.DENY, "isp:fixture isp"), source).isDenied());
        assertFalse(evaluate(rule(Effect.DENY, "isp:Fixture"), source).isDenied());
        assertTrue(evaluate(rule(Effect.DENY, "operator:Fixture Operator"), source).isDenied());
        assertTrue(evaluate(rule(Effect.DENY, "country:DE"), source).isDenied());
        assertFalse(evaluate(rule(Effect.DENY, "type:HOSTING"), source).isDenied());
    }
    @Test void rulesPersistAllMetadataAndRejectInvalidSelectorsBeforeWrite() throws Exception {
        AccessRuleStore store = new AccessRuleStore(directory);
        for (String target : new String[]{"asn:AS4294967295", "isp:Fixture ISP", "operator:Fixture Operator", "type:RELAY", "risk:ProxyCheckVpnProvider#0:80", "country:PT"}) store.add(Effect.DENY, Scope.VPN, target, 0, "Fixture");
        assertEquals(6, new AccessRuleStore(directory).snapshot().size());
        for (String target : new String[]{"asn:0", "asn:4294967296", "risk:80", "risk:a:101", "risk:a:-1", "type:ANY", "country:de", "operator:bad\nline"})
            assertThrows(IllegalArgumentException.class, () -> store.add(Effect.DENY, Scope.VPN, target, 0, "Fixture"));
        assertEquals(6, store.snapshot().size());
    }
    @Test void cachedSourceMetadataSurvivesAndMalformedScoresCannotBecomeDecisions() {
        VpnResult result = new VpnResult("192.0.2.1", true);
        result.setVotes(Collections.singletonList(source("a", 15169L, 80, true))); result.setCachedOn(System.currentTimeMillis());
        String encoded = CacheCodec.encode(result);
        VpnResult cached = CacheCodec.vpn(encoded, "192.0.2.1", 60000).get();
        assertTrue(evaluate(rule(Effect.DENY, "risk:a:80"), cached.getVotes().get(0)).isDenied());
        assertFalse(CacheCodec.vpn(encoded.replace("\"risk\":80", "\"risk\":180"), "192.0.2.1", 60000).isPresent());
    }
}
