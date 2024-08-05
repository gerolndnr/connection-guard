package com.github.gerolndnr.connectionguard.core.vpn;

public class VpnResult {
    private final String ipAddress;
    private boolean isVpn;
    private long cachedOn;

    public VpnResult(String ipAddress, boolean isVpn) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isVpn() {
        return isVpn;
    }

    public long getCachedOn() {
        return cachedOn;
    }

    public void setCachedOn(long cachedOn) {
        this.cachedOn = cachedOn;
    }

    public void setVpn(boolean vpn) {
        isVpn = vpn;
    }
}
