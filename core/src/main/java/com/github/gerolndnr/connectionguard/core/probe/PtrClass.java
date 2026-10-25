package com.github.gerolndnr.connectionguard.core.probe;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the network's own reverse-DNS name says about an address (the same classes as Connection Guard Intel's
 * intel/rdns.py, measured on the benchmark cohorts). Only the class is kept, never the name.
 */
public enum PtrClass {
    VPN, SERVER, RESIDENTIAL, OTHER, NONE;

    private static final Pattern VPN_BRANDS = Pattern.compile("(nordvpn|mullvad|protonvpn|proton\\.me|expressvpn|expressnetw|surfshark|"
            + "privateinternetaccess|privacy\\.network|cyberghost|cg-dialup|ipvanish|purevpn|ptoserver|pointtoserver|windscribe|"
            + "whiskergalaxy|hidemyass|hma\\.rocks|hideservers|torguard|vyprvpn|strongvpn|ivpn|azirevpn|ovpn\\.com|airvpn|privado|"
            + "hotspotshield|tunnelbear|zenmate|astrill|31173\\.se|packethub|cyberzone|\\bvpn\\b|-vpn|vpn-)");
    private static final Pattern RESIDENTIAL_WORDS = Pattern.compile("(^|[.\\-_\\d])(dsl|adsl|vdsl|xdsl|dyn|dynamic|dynip|dhcp|pool|"
            + "ppp|pppoe|dialup|dial|cable|cpe|customer|cust|subscriber|sub|broadband|bb|fiber|fibre|ftth|fttx|fttb|home|resi|"
            + "residential|user|client|wireless|wifi|lte|mobile|gprs|umts|3g|4g|5g|cgnat|nat|catv|hsd|kabel|dip|dip0|business|biz|"
            + "abo|fixed|access|pat)([.\\-_\\d]|$)");
    private static final Pattern SERVER_WORDS = Pattern.compile("(^|[.\\-_\\d])(server|srv|vps|vds|dedi|dedicated|hosted|hosted-by|"
            + "hosting|baremetal|colo|colocation|datacenter|rack|compute|instance|ec2|kvm|xen)([.\\-_\\d]|$)");

    /** @param name the PTR name, or null/the address itself when there is none */
    public static PtrClass of(String name, String address) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(address)) return NONE;
        String low = name.toLowerCase(Locale.ROOT);
        if (low.endsWith(".")) low = low.substring(0, low.length() - 1);
        if (VPN_BRANDS.matcher(low).find()) return VPN;
        // Only the labels before the registered domain describe the address.
        String[] labels = low.split("\\.");
        String head = labels.length <= 2 ? low : String.join(".", java.util.Arrays.copyOf(labels, labels.length - 2));
        if (SERVER_WORDS.matcher(head).find()) return SERVER;
        if (RESIDENTIAL_WORDS.matcher(head).find()) return RESIDENTIAL;
        return OTHER;
    }
}
