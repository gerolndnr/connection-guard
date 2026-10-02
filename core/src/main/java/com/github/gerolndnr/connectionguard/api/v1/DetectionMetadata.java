package com.github.gerolndnr.connectionguard.api.v1;

import java.util.*;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;

/** Immutable source observations. Absent fields mean unknown, never an inferred zero/false. */
public final class DetectionMetadata {
    public enum Type { VPN, PROXY, TOR, RELAY, HOSTING }
    private final Map<Type, Boolean> types;
    private final Long asn;
    private final String isp, operator, country;
    private final Integer risk, confidence;
    public DetectionMetadata(Map<Type, Boolean> types, Long asn, String isp, String operator,
                             String country, Integer risk, Integer confidence) {
        Map<Type, Boolean> copy = new EnumMap<>(Type.class);
        if (types != null) copy.putAll(types);
        this.types = Collections.unmodifiableMap(copy);
        this.asn = asn; this.isp = text(isp); this.operator = text(operator); this.country = text(country);
        this.risk = risk; this.confidence = confidence;
        toCore().validate();
    }
    private static String text(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    public static DetectionMetadata empty() { return new DetectionMetadata(null, null, null, null, null, null, null); }
    public Map<Type, Boolean> getTypes() { return types; }
    public Long getAsn() { return asn; }
    public String getIsp() { return isp; }
    public String getOperator() { return operator; }
    public String getCountry() { return country; }
    public Integer getRisk() { return risk; }
    public Integer getConfidence() { return confidence; }
    private DetectionDetails toCore() {
        Map<DetectionDetails.Type, Boolean> values = new EnumMap<>(DetectionDetails.Type.class);
        types.forEach((type, flag) -> values.put(DetectionDetails.Type.valueOf(type.name()), flag));
        return new DetectionDetails(values, asn, isp, operator, country, risk, confidence);
    }
}
