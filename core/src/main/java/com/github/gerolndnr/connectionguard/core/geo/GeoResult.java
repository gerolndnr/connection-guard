package com.github.gerolndnr.connectionguard.core.geo;

public class GeoResult {
    String ipAddress;
    String countryName;
    String cityName;
    String ispName;

    public GeoResult(String ipAddress, String countryName, String cityName, String ispName) {
        this.ipAddress = ipAddress;
        this.countryName = countryName;
        this.cityName = cityName;
        this.ispName = ispName;
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
}
