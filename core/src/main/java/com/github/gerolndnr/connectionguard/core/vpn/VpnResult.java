package com.github.gerolndnr.connectionguard.core.vpn;

public class VpnResult {
    private final String ipAddress;
    private boolean isVpn;

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

    public void setVpn(boolean vpn) {
        isVpn = vpn;
    }
}
