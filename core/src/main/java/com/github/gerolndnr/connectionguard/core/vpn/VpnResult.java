package com.github.gerolndnr.connectionguard.core.vpn;

import java.util.Optional;

public class VpnResult {
    private final String ipAddress;
    private Optional<String> vpnProviderName;
    private boolean isVpn;
    private long cachedOn;

    public VpnResult(String ipAddress, boolean isVpn) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
        this.vpnProviderName = Optional.empty();
    }

    public VpnResult(String ipAddress, boolean isVpn, Optional<String> vpnProviderName) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
        this.vpnProviderName = vpnProviderName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isVpn() {
        return isVpn;
    }

    public Optional<String> getVpnProviderName() {
        return vpnProviderName;
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
