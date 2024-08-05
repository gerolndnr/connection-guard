package com.github.gerolndnr.connectionguard.core.geo;

public class GeoResult {
    private String ipAddress;
    private String countryName;
    private String cityName;
    private String ispName;
    private long cachedOn;

    public GeoResult(String ipAddress, String countryName, String cityName, String ispName) {
        this.ipAddress = ipAddress;
        this.countryName = countryName;
        this.cityName = cityName;
        this.ispName = ispName;
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
}
