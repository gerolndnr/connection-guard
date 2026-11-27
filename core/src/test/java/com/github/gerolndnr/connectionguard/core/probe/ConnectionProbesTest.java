package com.github.gerolndnr.connectionguard.core.probe;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionProbesTest {
    private static TcpSample sample(long rttMicros, long sndMss) { return new TcpSample(rttMicros, 500, sndMss, 1448, true); }

    @Test void segmentSizesNameAccessTechnologiesPerFamily() {
        assertEquals(ConnectionProbes.Mss.STANDARD, ConnectionProbes.classify(1460, false));
        assertEquals(ConnectionProbes.Mss.STANDARD, ConnectionProbes.classify(1440, true));
        assertEquals(ConnectionProbes.Mss.PPPOE, ConnectionProbes.classify(1452, false));
        assertEquals(ConnectionProbes.Mss.DS_LITE, ConnectionProbes.classify(1420, false));
        assertEquals(ConnectionProbes.Mss.WIREGUARD, ConnectionProbes.classify(1380, false));
        assertEquals(ConnectionProbes.Mss.WIREGUARD, ConnectionProbes.classify(1360, true));
        // IPv6 has no DS-Lite; 1400 there is 1420 in IPv4 terms and counts as a tunnel.
        assertEquals(ConnectionProbes.Mss.TUNNEL, ConnectionProbes.classify(1400, true));
        assertEquals(ConnectionProbes.Mss.TUNNEL, ConnectionProbes.classify(1250, false));
        assertEquals(ConnectionProbes.Mss.UNKNOWN, ConnectionProbes.classify(1436, false));
        assertEquals(ConnectionProbes.Mss.UNKNOWN, ConnectionProbes.classify(0, false));
    }

    @Test void peerMssAddsTheTimestampOption() {
        assertEquals(1460, new TcpSample(1, 1, 1448, 1448, true).peerMss());
        assertEquals(1460, new TcpSample(1, 1, 1460, 1460, false).peerMss());
        assertFalse(TcpSample.read(new Object()).isPresent());
        assertFalse(TcpSample.read(null).isPresent());
    }

    @Test void aPingFarAboveTheKernelRoundTripMeansATcpTerminatingProxy() {
        ConnectionProbes.Assessment proxy = ConnectionProbes.assess(sample(1_200, 1448), Arrays.asList(95L, 52L, 61L), false, 40);
        assertEquals(52, proxy.minPingMillis);
        assertEquals(51, proxy.gapMillis);
        assertTrue(proxy.proxySuspected);
        assertTrue(proxy.describe().endsWith("TCP-terminating proxy suspected"));
    }

    @Test void aFarDirectPlayerIsNotSuspected() {
        ConnectionProbes.Assessment far = ConnectionProbes.assess(sample(51_000, 1448), Arrays.asList(70L, 53L, 58L), false, 40);
        assertEquals(2, far.gapMillis);
        assertFalse(far.proxySuspected);
    }

    @Test void oneGapIsNotEnoughAndUnmeasuredPingsAreIgnored() {
        assertFalse(ConnectionProbes.assess(sample(1_000, 1448), Collections.singletonList(90L), false, 40).proxySuspected);
        ConnectionProbes.Assessment none = ConnectionProbes.assess(sample(1_000, 1448), Arrays.asList(-1L, -1L), false, 40);
        assertEquals(0, none.pings);
        assertFalse(none.proxySuspected);
        assertTrue(none.describe().contains("ping=pending"));
    }

    @Test void playersBehindAProxyOrWithoutTcpInfoAreOnlyCounted() {
        List<String> logged = new ArrayList<>();
        ConnectionProbes probes = new ConnectionProbes(40, logged::add);
        UUID player = UUID.randomUUID();
        probes.joined(player, "Steve", "192.0.2.1|50000", false, true, new Object());
        probes.joined(UUID.randomUUID(), "Alex", "192.0.2.2|50000", false, false, new Object());
        probes.sample(player, 50, new Object(), true);
        assertFalse(probes.assessment(player).isPresent());
        assertTrue(logged.isEmpty());
        assertTrue(probes.describe().get(0).contains("measured=0 proxy-suspected=0 no-tcp-info=1 behind-tcp-proxy=1"));
    }
}
