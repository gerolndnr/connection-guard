package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPolicyTest {
    private static final String IP = "192.0.2.1";
    private GuardSettings settings(Object... pairs) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("behavior.vpn.kick-player", true); fields.put("behavior.geo.kick-player", true);
        for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
        return GuardSettings.read(fields::get, Collections.emptyList());
    }
    private GeoLookup geo() { return new GeoLookup(Optional.of(new GeoResult(IP, "DE", "", "Unknown")), FailureReason.NONE, false, 10); }
    private AccessRule rule(String id, AccessRule.Effect effect, AccessRule.Scope scope, String target, long expiry) {
        return new AccessRule(id, effect, scope, target, expiry, "Synthetic case");
    }
    private ConnectionPolicy.Evaluation evaluate(GuardSettings settings, List<AccessRule> rules, boolean vpnExempt,
                                                boolean geoExempt, VpnResult vpn, GeoLookup geo) {
        return ConnectionPolicy.evaluate(settings, rules, IP, null, false, vpnExempt, geoExempt, vpn, geo, "geo.fixture", 100);
    }
    @Test void modeAndKickSettingsControlEffectiveDenialWithoutChangingEvidence() {
        VpnResult vpn = new VpnResult(IP, true);
        ConnectionPolicy.Evaluation observe = evaluate(settings("operation.mode", "OBSERVE"), Collections.emptyList(), false, false, vpn, geo());
        assertTrue(observe.vpnFlag); assertNull(observe.earlyDenial); assertNull(observe.denial);
        assertEquals(Reason.VPN_FLAG, evaluate(settings(), Collections.emptyList(), false, false, vpn, geo()).denial);
        ConnectionPolicy.Evaluation notifyOnly = evaluate(settings("behavior.vpn.kick-player", false), Collections.emptyList(), false, false, vpn, geo());
        assertTrue(notifyOnly.vpnFlag); assertNull(notifyOnly.denial);
    }
    @Test void literalDenyAddedDuringPermissionWaitWinsOverBothScopeExemptions() {
        List<AccessRule> rules = Collections.singletonList(rule("block", AccessRule.Effect.DENY, AccessRule.Scope.ALL, IP, 0));
        ConnectionPolicy.Evaluation result = evaluate(settings(), rules, true, true, new VpnResult(IP, false), geo());
        assertEquals(Reason.ACCESS_RULE, result.earlyDenial); assertEquals(Reason.ACCESS_RULE, result.denial);
        assertEquals("block", result.vpnRule.getRule().get().getId()); assertEquals("block", result.geoRule.getRule().get().getId());
        assertNull(evaluate(settings("operation.mode", "OBSERVE"), rules, true, true, new VpnResult(IP, false), geo()).denial);
    }
    @Test void explicitAllowWinsOverMetadataDenyButNotLiteralDeny() {
        List<AccessRule> rules = new ArrayList<>();
        rules.add(rule("allow", AccessRule.Effect.ALLOW, AccessRule.Scope.ALL, IP, 0));
        rules.add(rule("country", AccessRule.Effect.DENY, AccessRule.Scope.ALL, "country:DE", 0));
        assertNull(evaluate(settings(), rules, false, false, new VpnResult(IP, true), geo()).denial);
        rules.add(rule("deny", AccessRule.Effect.DENY, AccessRule.Scope.VPN, "192.0.2.0/24", 0));
        assertEquals(Reason.ACCESS_RULE, evaluate(settings(), rules, false, false, new VpnResult(IP, true), geo()).denial);
    }
    @Test void permissionExemptionPreservesMetadataExemptionSemantics() {
        List<AccessRule> rules = Collections.singletonList(rule("country", AccessRule.Effect.DENY, AccessRule.Scope.GEO, "country:DE", 0));
        assertEquals(Reason.ACCESS_RULE, evaluate(settings(), rules, false, false, new VpnResult(IP, false), geo()).denial);
        assertNull(evaluate(settings(), rules, false, true, new VpnResult(IP, false), geo()).denial);
    }
    @Test void unknownClosedPrecedesPositiveFlagButExemptionAndObserveSuppressIt() {
        GeoLookup unavailable = new GeoLookup(Optional.empty(), FailureReason.TIMEOUT, false, 100);
        GuardSettings strict = settings("failure-policy.geo", "CLOSED");
        assertEquals(Reason.LOOKUP_UNAVAILABLE, evaluate(strict, Collections.emptyList(), false, false, new VpnResult(IP, true), unavailable).denial);
        assertEquals(Reason.VPN_FLAG, evaluate(strict, Collections.emptyList(), false, true, new VpnResult(IP, true), unavailable).denial);
        assertNull(evaluate(settings("failure-policy.geo", "CLOSED", "operation.mode", "OBSERVE"), Collections.emptyList(), false, false, new VpnResult(IP, true), unavailable).denial);
    }
    @Test void unknownNeverBecomesNegativeAndOpenPreservesUnknownEvidence() {
        VpnResult unknown = new VpnResult(IP, false); unknown.setUnknown(FailureReason.HTTP_ERROR);
        ConnectionPolicy.Evaluation open = evaluate(settings(), Collections.emptyList(), false, false, unknown, geo());
        assertNull(open.denial); assertFalse(open.vpnFlag); assertEquals(ProviderVote.Status.UNKNOWN, open.vpn.getStatus());
        assertEquals(Reason.LOOKUP_UNAVAILABLE, evaluate(settings("failure-policy.vpn", "CLOSED"), Collections.emptyList(), false, false, unknown, geo()).denial);
    }
    @Test void unknownMetadataBlocksGrantAndFollowsExplicitFailurePolicy() {
        List<AccessRule> rules = Collections.singletonList(rule("asn", AccessRule.Effect.ALLOW, AccessRule.Scope.VPN, "asn:15169", 0));
        ConnectionPolicy.Evaluation strict = evaluate(settings("failure-policy.vpn", "CLOSED"), rules, false, false, new VpnResult(IP, false), geo());
        assertTrue(strict.vpnRule.isUnresolved()); assertEquals(Reason.LOOKUP_UNAVAILABLE, strict.denial);
    }
    @Test void rulesAndEvidenceUseTheSameExclusiveExpiryBoundary() {
        List<AccessRule> rules = Collections.singletonList(rule("expired", AccessRule.Effect.DENY, AccessRule.Scope.ALL, IP, 100));
        VpnResult vpn = new VpnResult(IP, true); vpn.setValidUntil(100);
        GeoLookup geo = geo(); geo.getResult().get().setValidUntil(100);
        ConnectionPolicy.Evaluation result = evaluate(settings(), rules, false, false, vpn, geo);
        assertNull(result.denial); assertFalse(result.vpnFlag); assertFalse(result.geoFlag);
        assertFalse(result.vpnRule.getRule().isPresent()); assertFalse(result.geoRule.getRule().isPresent());
        assertEquals(ProviderVote.Status.UNKNOWN, result.vpn.getStatus()); assertEquals(FailureReason.STALE_DATA, result.geo.getReason());
    }
    @Test void expiredIndependentSourceCannotLowerCapturedQuorum() {
        VpnResult vpn = new VpnResult(IP, true); vpn.setPositiveThreshold(2);
        vpn.setVotes(Arrays.asList(new ProviderVote("live", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1),
                new ProviderVote("expired", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1, DetectionDetails.empty(), 100, null)));
        ConnectionPolicy.Evaluation result = evaluate(settings(), Collections.emptyList(), false, false, vpn, geo());
        assertEquals(ProviderVote.Status.UNKNOWN, result.vpn.getStatus()); assertFalse(result.vpnFlag);
        vpn.setPositiveThreshold(1);
        assertEquals(Reason.VPN_FLAG, evaluate(settings(), Collections.emptyList(), false, false, vpn, geo()).denial);
    }
    @Test void geoBlacklistWhitelistAndMissingCountryRemainDistinct() {
        assertEquals(Reason.GEO_FLAG, evaluate(settings("behavior.geo.list", Arrays.asList("DE")), Collections.emptyList(), false, false, new VpnResult(IP, false), geo()).denial);
        assertNull(evaluate(settings("behavior.geo.type", "WHITELIST", "behavior.geo.list", Arrays.asList("DE")), Collections.emptyList(), false, false, new VpnResult(IP, false), geo()).denial);
        assertEquals(Reason.GEO_FLAG, evaluate(settings("behavior.geo.type", "WHITELIST"), Collections.emptyList(), false, false, new VpnResult(IP, false), geo()).denial);
        assertFalse(evaluate(settings("behavior.geo.type", "WHITELIST"), Collections.emptyList(), false, false, new VpnResult(IP, false), new GeoLookup(Optional.empty(), FailureReason.NO_EVIDENCE, false, 0)).geoFlag);
    }
    @Test void inFlightCountryPolicyCannotBeMutatedByItsSourceList() {
        List<String> countries = new ArrayList<>(Arrays.asList("DE"));
        GuardSettings selected = settings("behavior.geo.list", countries); countries.clear();
        assertEquals(Reason.GEO_FLAG, evaluate(selected, Collections.emptyList(), false, false, new VpnResult(IP, false), geo()).denial);
        assertThrows(UnsupportedOperationException.class, () -> selected.countries.clear());
    }
    @Test void malformedCountryAndKickDraftsFailBeforeActivationWithoutValues() {
        for (Object value : Arrays.asList("DE", Arrays.asList("de"), Arrays.asList(true), Arrays.asList("SECRET-CREDENTIAL"))) {
            Exception failure = assertThrows(IllegalArgumentException.class, () -> settings("behavior.geo.list", value));
            assertFalse(failure.getMessage().contains("SECRET-CREDENTIAL"));
        }
        assertThrows(IllegalArgumentException.class, () -> settings("behavior.vpn.kick-player", "yes"));
    }
    @Test void trustedIdentityIsRequiredAndBothScopesShareTheIdentityView() {
        UUID uuid = UUID.fromString("00000000-0000-4000-8000-000000000001");
        List<AccessRule> rules = Collections.singletonList(rule("identity", AccessRule.Effect.ALLOW, AccessRule.Scope.ALL, uuid.toString(), 0));
        assertEquals(Reason.VPN_FLAG, ConnectionPolicy.evaluate(settings(), rules, IP, uuid, false, false, false, new VpnResult(IP, true), geo(), "geo.fixture", 100).denial);
        ConnectionPolicy.Evaluation verified = ConnectionPolicy.evaluate(settings(), rules, IP, uuid, true, false, false, new VpnResult(IP, true), geo(), "geo.fixture", 100);
        assertNull(verified.denial); assertTrue(verified.vpnRule.isBypassed()); assertTrue(verified.geoRule.isBypassed());
    }
    @Test void evidenceForAnotherAddressIsRejectedIncludingStaleGeo() {
        assertThrows(IllegalArgumentException.class, () -> evaluate(settings(), Collections.emptyList(), false, false, new VpnResult("198.51.100.1", true), geo()));
        GeoResult other = new GeoResult("198.51.100.1", "DE", "", "Unknown"); other.setValidUntil(1);
        assertThrows(IllegalArgumentException.class, () -> evaluate(settings(), Collections.emptyList(), false, false, new VpnResult(IP, false), new GeoLookup(Optional.of(other), FailureReason.NONE, false, 0)));
    }
    @Test void mappedIpv4IsTheSameLiteralIdentityWithoutDns() {
        assertEquals(Reason.ACCESS_RULE, ConnectionPolicy.evaluate(settings(), Collections.singletonList(rule("literal", AccessRule.Effect.DENY, AccessRule.Scope.ALL, IP, 0)), "::ffff:192.0.2.1", null, false, false, false, new VpnResult(IP, false), geo(), "geo.fixture", 100).denial);
    }
}
