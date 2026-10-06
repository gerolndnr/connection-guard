package com.github.gerolndnr.connectionguard.api.v1;

import java.util.*;
import java.math.BigDecimal;
import com.github.gerolndnr.connectionguard.core.lookup.DetectionDetails;

/** Immutable source observations. Absent fields mean unknown, never an inferred zero/false. */
public final class DetectionMetadata {
    public enum Type { VPN, PROXY, TOR, RELAY, HOSTING }
    private final Map<Type, Boolean> types;
    private final Long asn;
    private final String isp, operator, country;
    private final Integer risk, confidence;
    private final BigDecimal exactRisk;
    private final Long dataAsOf;
    public DetectionMetadata(Map<Type, Boolean> types, Long asn, String isp, String operator,
                             String country, Integer risk, Integer confidence) {
        this(types, asn, isp, operator, country, risk, confidence, null);
    }
    private DetectionMetadata(Map<Type, Boolean> types, Long asn, String isp, String operator,
                              String country, Integer risk, Integer confidence, BigDecimal exactRisk) {
        this(types, asn, isp, operator, country, risk, confidence, exactRisk, null);
    }
    private DetectionMetadata(Map<Type, Boolean> types, Long asn, String isp, String operator,
                              String country, Integer risk, Integer confidence, BigDecimal exactRisk, Long dataAsOf) {
        Map<Type, Boolean> copy = new EnumMap<>(Type.class);
        if (types != null) copy.putAll(types);
        this.types = Collections.unmodifiableMap(copy);
        this.asn = asn; this.isp = text(isp); this.operator = text(operator); this.country = text(country);
        this.risk = risk; this.confidence = confidence; this.exactRisk = exactRisk; this.dataAsOf = dataAsOf;
        toCore().validate();
    }
    /** Additive API: preserves decimals without changing the original constructor/getRisk contract. */
    public static DetectionMetadata withExactRisk(Map<Type, Boolean> types, Long asn, String isp, String operator,
                                                 String country, BigDecimal risk, Integer confidence) {
        BigDecimal exact = DetectionDetails.normalizeRisk(risk);
        Integer integer = null;
        if (exact != null) try { integer = exact.intValueExact(); } catch (ArithmeticException fractional) { /* unknown to legacy readers */ }
        return new DetectionMetadata(types, asn, isp, operator, country, integer, confidence, exact);
    }
    private static String text(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    public static DetectionMetadata empty() { return new DetectionMetadata(null, null, null, null, null, null, null); }
    public Map<Type, Boolean> getTypes() { return types; }
    public Long getDataAsOf() { return dataAsOf; }
    public DetectionMetadata withDataAsOf(Long timestamp) { return new DetectionMetadata(types, asn, isp, operator, country, risk, confidence, exactRisk, timestamp); }
    public Long getAsn() { return asn; }
    public String getIsp() { return isp; }
    public String getOperator() { return operator; }
    public String getCountry() { return country; }
    /** Returns null for fractional values; use getExactRisk for source risk rules. */
    public Integer getRisk() { return risk; }
    public BigDecimal getExactRisk() { return exactRisk != null ? exactRisk : risk == null ? null : BigDecimal.valueOf(risk); }
    public Integer getConfidence() { return confidence; }
    private DetectionDetails toCore() {
        Map<DetectionDetails.Type, Boolean> values = new EnumMap<>(DetectionDetails.Type.class);
        types.forEach((type, flag) -> values.put(DetectionDetails.Type.valueOf(type.name()), flag));
        return DetectionDetails.withExactRisk(values, asn, isp, operator, country, getExactRisk(), confidence).withDataAsOf(dataAsOf);
    }
}
