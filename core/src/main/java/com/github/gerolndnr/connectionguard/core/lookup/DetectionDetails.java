package com.github.gerolndnr.connectionguard.core.lookup;

import java.util.*;

/** Source-specific observations, never inferred from a generic positive or missing field. */
public final class DetectionDetails {
    public enum Type { VPN, PROXY, TOR, RELAY, HOSTING }
    private final Map<Type, Boolean> classifications;
    private final Long asn;
    private final String isp, operator, country;
    private final Integer risk, confidence;
    public DetectionDetails(Map<Type, Boolean> classifications, Long asn, String isp, String operator,
                            String country, Integer risk, Integer confidence) {
        this.classifications = new EnumMap<>(Type.class);
        if (classifications != null) this.classifications.putAll(classifications);
        this.asn = asn; this.isp = text(isp); this.operator = text(operator); this.country = text(country);
        this.risk = risk; this.confidence = confidence;
        validate();
    }
    public static DetectionDetails empty() { return new DetectionDetails(null, null, null, null, null, null, null); }
    public void validate() {
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
    public Long getAsn() { return asn; }
    public String getIsp() { return isp; }
    public String getOperator() { return operator; }
    public String getCountry() { return country; }
    public Integer getRisk() { return risk; }
    public Integer getConfidence() { return confidence; }
    public String describe() { return "types=" + classifications + " asn=" + value(asn) + " isp=" + value(isp)
            + " operator=" + value(operator) + " country=" + value(country) + " risk=" + value(risk) + " confidence=" + value(confidence); }
    private static String value(Object value) { return value == null ? "UNKNOWN" : value.toString(); }
}
