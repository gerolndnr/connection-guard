package com.github.gerolndnr.connectionguard.core.policy;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Reason;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;

/** One side-effect-free policy for live adapters and saved-case replay. No providers or platform APIs. */
public final class ConnectionPolicy {
    private ConnectionPolicy() { }

    public static final class Evaluation {
        public final VpnResult vpn;
        public final GeoLookup geo;
        public final EvidencePolicy.Decision vpnRule, geoRule;
        public final boolean vpnFlag, geoFlag;
        /** Refusal before flag actions; null in OBSERVE. */
        public final Reason earlyDenial;
        /** Final effective refusal, including flag kick settings; null means allow. */
        public final Reason denial;
        private Evaluation(VpnResult vpn, GeoLookup geo, EvidencePolicy.Decision vpnRule,
                           EvidencePolicy.Decision geoRule, boolean vpnFlag, boolean geoFlag,
                           Reason earlyDenial, Reason denial) {
            this.vpn = vpn; this.geo = geo; this.vpnRule = vpnRule; this.geoRule = geoRule;
            this.vpnFlag = vpnFlag; this.geoFlag = geoFlag; this.earlyDenial = earlyDenial; this.denial = denial;
        }
    }

    public static Evaluation evaluate(GuardSettings settings, List<AccessRule> rules, String ip,
                                      UUID uuid, boolean trusted, boolean vpnExempt, boolean geoExempt,
                                      VpnResult rawVpn, GeoLookup rawGeo, String geoSource, long asOf) {
        Objects.requireNonNull(settings); Objects.requireNonNull(rules);
        String address = Exemptions.normalize(ip);
        if (asOf < 0 || !address.equals(Exemptions.normalize(rawVpn.getIpAddress())))
            throw new IllegalArgumentException("Policy evidence must belong to the requested address.");
        if (rawGeo.getResult().isPresent() && !address.equals(Exemptions.normalize(rawGeo.getResult().get().getIpAddress())))
            throw new IllegalArgumentException("Geo evidence must belong to the requested address.");
        // Freeze both scopes at the same time and rule version, including expiry boundaries.
        List<AccessRule> snapshot = new ArrayList<>(rules);
        VpnResult vpn = LookupFreshness.vpn(rawVpn, asOf);
        GeoLookup geo = LookupFreshness.geo(rawGeo, asOf);
        List<ProviderVote> sources = sources(vpn, geo, geoSource);
        EvidencePolicy.Decision vpnRule = EvidencePolicy.evaluate(snapshot, address, uuid, trusted, AccessRule.Scope.VPN, sources, asOf);
        EvidencePolicy.Decision geoRule = EvidencePolicy.evaluate(snapshot, address, uuid, trusted, AccessRule.Scope.GEO, sources, asOf);
        boolean vpnBypassed = vpnExempt || vpnRule.isBypassed(), geoBypassed = geoExempt || geoRule.isBypassed();
        Reason early = null;
        if (!settings.observe) {
            if (denied(vpnRule, vpnExempt) || denied(geoRule, geoExempt)) early = Reason.ACCESS_RULE;
            else if (!vpnBypassed && (vpn.getStatus() == ProviderVote.Status.UNKNOWN || vpnRule.isUnresolved()) && settings.vpnFailure == GuardSettings.FailurePolicy.CLOSED
                    || !geoBypassed && (geo.getReason() != FailureReason.NONE || geoRule.isUnresolved()) && settings.geoFailure == GuardSettings.FailurePolicy.CLOSED)
                early = Reason.LOOKUP_UNAVAILABLE;
        }
        boolean vpnFlag = vpn.isVpn() && !vpnBypassed;
        boolean geoFlag = !geoBypassed && geo.getResult().isPresent()
                && (settings.geoWhitelist != settings.countries.contains(geo.getResult().get().getCountryName()));
        Reason denial = early;
        if (denial == null && !settings.observe) {
            if (vpnFlag && settings.kickVpn) denial = Reason.VPN_FLAG;
            else if (geoFlag && settings.kickGeo) denial = Reason.GEO_FLAG;
        }
        return new Evaluation(vpn, geo, vpnRule, geoRule, vpnFlag, geoFlag, early, denial);
    }

    private static boolean denied(EvidencePolicy.Decision rule, boolean exempt) {
        // A literal DENY always wins, even if it was added while permission resolution was pending.
        // Provider-dependent metadata remains subject to a permission exemption, as before.
        return rule.isDenied() && (!exempt || !rule.getRule().get().isMetadata());
    }

    public static List<ProviderVote> sources(VpnResult vpn, GeoLookup geo, String geoSource) {
        List<ProviderVote> sources = new ArrayList<>(vpn.getVotes());
        if (geoSource != null && (geo.getResult().isPresent() || geo.getReason() != FailureReason.NONE)) {
            GeoResult result = geo.getResult().orElse(null);
            DetectionDetails details = result == null ? DetectionDetails.empty() : new DetectionDetails(null,
                    result.getAsn(), "Unknown".equalsIgnoreCase(result.getIspName()) ? null : result.getIspName(),
                    null, result.getCountryName(), null, null);
            sources.add(new ProviderVote(geoSource, result == null ? ProviderVote.Status.UNKNOWN : ProviderVote.Status.NEGATIVE,
                    result == null ? geo.getReason() : FailureReason.NONE, geo.getDurationMillis(), details,
                    result == null ? 0 : result.getValidUntil(), result == null ? null : result.getSourceVersion(), false));
        }
        return Collections.unmodifiableList(sources);
    }
}
