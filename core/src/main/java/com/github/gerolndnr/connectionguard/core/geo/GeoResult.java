package com.github.gerolndnr.connectionguard.core.geo;

public class GeoResult {
    private String ipAddress;
    private String countryName;
    private String cityName;
    private String ispName;
    private long cachedOn;
    private Long asn;
    private long validUntil;
    private String sourceVersion;

    public GeoResult(String ipAddress, String countryName, String cityName, String ispName) {
        this.ipAddress = ipAddress;
        this.countryName = countryName;
        this.cityName = cityName;
        this.ispName = ispName;
        validate();
    }

    public long getCachedOn() {
        return cachedOn;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getCountryName() {
        return countryName;
    }

    public String getCityName() {
        return cityName;
    }

    public String getIspName() {
        return ispName;
    }

    public void setCachedOn(long cachedOn) {
        this.cachedOn = cachedOn;
    }
    public Long getAsn() { return asn; }
    public long getValidUntil() { return validUntil; }
    public void setValidUntil(long until) { if (until < 0) throw new IllegalArgumentException("Invalid source expiry."); validUntil = until; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String version) { if (version != null && !version.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source version."); sourceVersion = version; }
    public void setAsn(Long asn) { if (asn != null && (asn < 1 || asn > 4294967295L)) throw new IllegalArgumentException("Invalid ASN."); this.asn = asn; }
    public void validate() {
        if (countryName == null || !countryName.matches("[A-Z]{2}") || !valid(cityName) || !valid(ispName)) throw new IllegalArgumentException("Invalid geo fields.");
        setAsn(asn);
        setValidUntil(validUntil); setSourceVersion(sourceVersion);
    }
    private static boolean valid(String value) { return value != null && value.length() <= 200 && !value.chars().anyMatch(Character::isISOControl) && value.indexOf('\u00a7') < 0; }
}
