package com.github.gerolndnr.connectionguard.core.rules;

import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Literal-only IPv4/IPv6 CIDR matching. IPv4-mapped /96..128 networks map to IPv4 /0..32. */
public final class IpNetwork {
    private final byte[] network;
    private final int prefix;
    private IpNetwork(byte[] network, int prefix) { this.network = network; this.prefix = prefix; }
    public static IpNetwork parse(String text) {
        String[] parts = text.split("/", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid CIDR network.");
        try {
            byte[] bytes = InetAddress.getByName(Exemptions.normalize(parts[0])).getAddress();
            int prefix = parts.length == 1 ? bytes.length * 8 : Integer.parseInt(parts[1]);
            if (parts.length == 2 && bytes.length == 4 && parts[0].contains(":")) {
                if (prefix < 96 || prefix > 128) throw new IllegalArgumentException("Mapped IPv6 prefix must be 96..128.");
                prefix -= 96;
            }
            if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalArgumentException("Invalid CIDR prefix.");
            mask(bytes, prefix);
            return new IpNetwork(bytes, prefix);
        } catch (UnknownHostException | NumberFormatException invalid) { throw new IllegalArgumentException("Invalid CIDR network."); }
    }
    public boolean contains(String ip) {
        try {
            byte[] candidate = InetAddress.getByName(Exemptions.normalize(ip)).getAddress();
            if (candidate.length != network.length) return false;
            mask(candidate, prefix); return Arrays.equals(candidate, network);
        } catch (UnknownHostException | IllegalArgumentException invalid) { return false; }
    }
    private static void mask(byte[] bytes, int prefix) {
        for (int i = 0; i < bytes.length; i++) {
            int bits = Math.min(8, Math.max(0, prefix - i * 8));
            bytes[i] &= (byte) (bits == 0 ? 0 : 0xff << (8 - bits));
        }
    }
    @Override public String toString() {
        try { return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix; }
        catch (UnknownHostException impossible) { throw new IllegalStateException(); }
    }
    public byte[] getNetworkBytes() { return network.clone(); }
    public int getPrefix() { return prefix; }
}
