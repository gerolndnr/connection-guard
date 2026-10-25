package com.github.gerolndnr.connectionguard.core.probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PtrClassTest {
    @Test void namesAreClassifiedLikeIntel() {
        assertEquals(PtrClass.NONE, PtrClass.of(null, "192.0.2.1"));
        assertEquals(PtrClass.NONE, PtrClass.of("192.0.2.1", "192.0.2.1"));
        assertEquals(PtrClass.VPN, PtrClass.of("de1234.nordvpn.com", "192.0.2.1"));
        assertEquals(PtrClass.VPN, PtrClass.of("vpn-exit-3.example.net.", "192.0.2.1"));
        assertEquals(PtrClass.RESIDENTIAL, PtrClass.of("p5b0c1a2b.dip0.t-ipconnect.de", "192.0.2.1"));
        assertEquals(PtrClass.RESIDENTIAL, PtrClass.of("cpe-1-2-3-4.cable.example.com", "192.0.2.1"));
        assertEquals(PtrClass.SERVER, PtrClass.of("server-192-0-2-1.fra.example.net", "192.0.2.1"));
        assertEquals(PtrClass.SERVER, PtrClass.of("vps123.example-hosting.com", "192.0.2.1"));
        assertEquals(PtrClass.OTHER, PtrClass.of("mail.example.org", "192.0.2.1"));
        // The registered domain itself does not count: "dynamic" here is the company, not the line.
        assertEquals(PtrClass.OTHER, PtrClass.of("www.dynamic.com", "192.0.2.1"));
    }
}
