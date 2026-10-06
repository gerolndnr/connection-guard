package com.github.gerolndnr.connectionguard.core.lookup;

import java.util.*;
import java.math.BigDecimal;

/** Source-specific observations, never inferred from a generic positive or missing field. */
public final class DetectionDetails {
    public enum Type { VPN, PROXY, TOR, RELAY, HOSTING }
    private final Map<Type, Boolean> classifications;
    private final Long asn;
    private final String isp, operator, country;
    private final Integer risk, confidence;
    private final BigDecimal exactRisk;
    private final Long dataAsOf;
    public DetectionDetails(Map<Type, Boolean> classifications, Long asn, String isp, String operator,
                            String country, Integer risk, Integer confidence) {
        this(classifications, asn, isp, operator, country, risk, confidence, null);
    }
    private DetectionDetails(Map<Type, Boolean> classifications, Long asn, String isp, String operator,
                             String country, Integer risk, Integer confidence, BigDecimal exactRisk) {
        this(classifications, asn, isp, operator, country, risk, confidence, exactRisk, null);
    }
    private DetectionDetails(Map<Type, Boolean> classifications, Long asn, String isp, String operator,
                             String country, Integer risk, Integer confidence, BigDecimal exactRisk, Long dataAsOf) {
        this.classifications = new EnumMap<>(Type.class);
        if (classifications != null) this.classifications.putAll(classifications);
        this.asn = asn; this.isp = text(isp); this.operator = text(operator); this.country = text(country);
        this.risk = risk; this.confidence = confidence; this.exactRisk = exactRisk; this.dataAsOf = dataAsOf;
        validate();
    }
    /** Exact source risk; legacy getRisk is null when the value is not an integer. */
    public static DetectionDetails withExactRisk(Map<Type, Boolean> types, Long asn, String isp, String operator,
                                                String country, BigDecimal risk, Integer confidence) {
        BigDecimal exact = normalizeRisk(risk);
        return new DetectionDetails(types, asn, isp, operator, country, integerRisk(exact), confidence, exact);
    }
    /** Bounded decimal representation; do not round provider facts or accept enormous exponents. */
    public static BigDecimal normalizeRisk(BigDecimal risk) {
        if (risk == null) return null;
        if (risk.precision() > 128 || risk.scale() < -1000 || risk.scale() > 1000
                || risk.signum() < 0 || risk.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("Invalid source risk.");
        BigDecimal normalized = risk.signum() == 0 ? BigDecimal.ZERO : risk.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
    private static Integer integerRisk(BigDecimal risk) {
        if (risk == null) return null;
        try { return risk.intValueExact(); }
        catch (ArithmeticException fractional) { return null; }
    }
    public static DetectionDetails empty() { return new DetectionDetails(null, null, null, null, null, null, null); }
    public void validate() {
        if (dataAsOf != null && dataAsOf <= 0) throw new IllegalArgumentException("Invalid source publication time.");
        if (exactRisk != null && !Objects.equals(risk, integerRisk(normalizeRisk(exactRisk))))
            throw new IllegalArgumentException("Inconsistent source risk.");
        if (classifications == null || classifications.containsKey(null) || classifications.containsValue(null) || classifications.size() > Type.values().length
                || (asn != null && (asn < 1 || asn > 4294967295L)) || !score(risk) || !score(confidence)
                || !validText(isp) || !validText(operator) || !validText(country)
                || (country != null && !country.matches("[A-Z]{2}"))) throw new IllegalArgumentException("Invalid detection details.");
    }
    private static boolean score(Integer score) { return score == null || (score >= 0 && score <= 100); }
    private static String text(String text) { return text == null || text.trim().isEmpty() ? null : text.trim(); }
    private static boolean validText(String text) { return text == null || (text.length() <= 200 && !text.chars().anyMatch(Character::isISOControl) && text.indexOf('\u00a7') < 0); }
    public Map<Type, Boolean> getClassifications() { return Collections.unmodifiableMap(classifications); }
    public Boolean get(Type type) { return classifications.get(type); }
    public Long getDataAsOf() { return dataAsOf; }
    public DetectionDetails withDataAsOf(Long timestamp) { return new DetectionDetails(classifications, asn, isp, operator, country, risk, confidence, exactRisk, timestamp); }
    public Long getAsn() { return asn; }
    public String getIsp() { return isp; }
    public String getOperator() { return operator; }
    public String getCountry() { return country; }
    /** Compatibility accessor: fractional risk is unknown to integer-only consumers. */
    public Integer getRisk() { return risk; }
    public BigDecimal getExactRisk() { return exactRisk != null ? exactRisk : risk == null ? null : BigDecimal.valueOf(risk); }
    public Integer getConfidence() { return confidence; }
    public String describe() { return "types=" + classifications + " asn=" + value(asn) + " isp=" + value(isp)
            + " operator=" + value(operator) + " country=" + value(country) + " risk=" + value(getExactRisk()) + " confidence=" + value(confidence) + " as_of=" + value(dataAsOf); }
    private static String value(Object value) { return value == null ? "UNKNOWN" : value.toString(); }
}
