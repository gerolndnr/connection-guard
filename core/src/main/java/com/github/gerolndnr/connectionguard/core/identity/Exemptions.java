package com.github.gerolndnr.connectionguard.core.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

/** Literal addresses and verified UUIDs only. Never resolve DNS or trust a claimed login name. */
public final class Exemptions {
    private Exemptions() { }
    public static boolean matches(List<String> entries, String ip, UUID uuid, boolean trustedIdentity) {
        if (entries == null) return false;
        String normalized = normalize(ip);
        for (String entry : entries) {
            if (trustedIdentity && uuid != null && uuid.toString().equalsIgnoreCase(entry)) return true;
            try { if (normalized.equals(normalize(entry))) return true; }
            catch (IllegalArgumentException ignored) { /* Names and malformed entries cannot bypass checks. */ }
        }
        return false;
    }
    public static String normalize(String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > 45 || literal.indexOf('%') >= 0
                || !literal.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Use a literal IPv4 or IPv6 address.");
        if (literal.indexOf(':') < 0) {
            String[] parts = literal.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address.");
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.startsWith("0")))
                    throw new IllegalArgumentException("Invalid IPv4 address.");
                int value = Integer.parseInt(part);
                if (value > 255) throw new IllegalArgumentException("Invalid IPv4 address.");
            }
        }
        try { return InetAddress.getByName(literal).getHostAddress(); }
        catch (UnknownHostException error) { throw new IllegalArgumentException("Invalid IP address."); }
    }
}
