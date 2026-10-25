package com.github.gerolndnr.connectionguard.core.probe;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ProbeCountsTest {
    @TempDir Path dir;

    private static JsonObject record(String json) { return new Gson().fromJson(json, JsonObject.class); }
    private static Map<String, Long> counts(JsonObject window) {
        Map<String, Long> out = new TreeMap<>();
        for (JsonElement e : window.getAsJsonArray("counts")) {
            JsonObject j = e.getAsJsonObject();
            out.put(j.get("s").getAsString() + "|" + j.get("v").getAsString() + "|" + j.get("l").getAsString(), j.get("n").getAsLong());
        }
        return out;
    }

    @Test void labelsFollowConnectionGuardsVerdict() {
        assertEquals("unlabelled", ProbeCounts.label(record("{}")));
        assertEquals("vpn", ProbeCounts.label(record("{\"verdict\":{\"vpn\":\"POSITIVE\",\"sources\":[]}}")));
        assertEquals("vpn", ProbeCounts.label(record("{\"verdict\":{\"vpn\":\"UNKNOWN\",\"sources\":[{\"status\":\"POSITIVE\",\"types\":[\"PROXY\"]}]}}")));
        assertEquals("hosting", ProbeCounts.label(record("{\"verdict\":{\"vpn\":\"NEGATIVE\",\"sources\":[{\"status\":\"NEGATIVE\",\"types\":[\"HOSTING\"]}]}}")));
        assertEquals("clean", ProbeCounts.label(record("{\"verdict\":{\"vpn\":\"NEGATIVE\",\"sources\":[]}}")));
        assertEquals("unlabelled", ProbeCounts.label(record("{\"verdict\":{\"vpn\":\"EXEMPT\",\"sources\":[]}}")));
    }

    @Test void aWindowCountsSignalValuesPerLabelIncludingPerNetworkSignals() {
        ProbeCounts counts = new ProbeCounts();
        // Three accounts behind one network, far apart in ping, one skin shared: a typical shared exit.
        for (int i = 0; i < 3; i++) counts.add(record("{\"joined\":true,\"complete\":true,\"family\":4,\"net\":\"n1\",\"acct\":\"a" + i
                + "\",\"tcp\":{\"mss_class\":\"wireguard\",\"timestamps\":true,\"rttvar_ms\":3.0},\"ping\":{\"min_ms\":" + (20 + i * 90)
                + ",\"gap_ms\":55,\"proxy_suspected\":true},\"client\":{\"brand\":\"Fabric 0.16\",\"locale\":\"de_de\",\"skin\":\"s1\",\"protocol\":774},"
                + "\"verdict\":{\"vpn\":\"POSITIVE\",\"country\":\"NL\",\"sources\":[]}}"));
        counts.add(record("{\"joined\":true,\"complete\":false,\"family\":6,\"net\":\"n2\",\"acct\":\"b\",\"client\":{\"brand\":\"\\u00e4\\ud83d\\ude00weird\","
                + "\"bedrock_os\":\"GOOGLE\"},\"verdict\":{\"vpn\":\"NEGATIVE\",\"sources\":[{\"status\":\"NEGATIVE\",\"types\":[\"HOSTING\"]}]}}"));
        counts.add(record("{\"joined\":false,\"complete\":false,\"family\":4,\"net\":\"n3\"}"));
        assertEquals(5, counts.size());
        JsonObject window = counts.drain();
        assertEquals(5, window.get("logins").getAsLong());
        Map<String, Long> c = counts(window);
        assertEquals(3, c.get("all|all|vpn")); assertEquals(1, c.get("all|all|hosting")); assertEquals(1, c.get("all|all|unlabelled"));
        assertEquals(3, c.get("mss_class|wireguard|vpn"));
        assertEquals(3, c.get("ping_gap|<80|vpn"));
        assertEquals(3, c.get("brand|fabric|vpn"));
        assertEquals(1, c.get("brand|other|hosting"), "free text never leaves: unknown brands are 'other'");
        assertEquals(3, c.get("locale_country_mismatch|true|vpn"));
        assertEquals(3, c.get("skin_shared_by_3plus_accounts|true|vpn"));
        assertEquals(3, c.get("accounts_per_net|<5|vpn"));
        assertEquals(3, c.get("ping_spread_per_net|<80|vpn"));
        assertEquals(1, c.get("mobile_on_hosting|true|hosting"));
        assertEquals(1, c.get("left_early|true|hosting"));
        assertEquals(1, c.get("joined|false|unlabelled"));
        assertFalse(c.containsKey("new_account|true|vpn"), "new accounts count only after a week");
        for (String key : c.keySet()) assertTrue(key.matches("[a-z0-9_]{1,40}\\|[a-z0-9_<>=.\\-]{1,24}\\|(vpn|hosting|clean|unlabelled)"), key);
        String text = window.toString();
        assertFalse(text.contains("n1") || text.contains("\"a1\"") || text.contains("s1") || text.contains("de_de"), text);
        assertEquals(0, counts.size(), "records are forgotten once drained");
    }

    @Test void smallWindowsWaitAndFailedSyncsComeBack() {
        ProbeCounts counts = new ProbeCounts();
        for (int i = 0; i < 4; i++) counts.add(record("{\"joined\":true,\"complete\":true,\"tcp\":{\"mss_class\":\"standard\"}}"));
        assertNull(counts.drain(), "four logins say too much about each one");
        counts.add(record("{\"joined\":true,\"complete\":true,\"tcp\":{\"mss_class\":\"pppoe\"}}"));
        JsonObject window = counts.drain();
        assertEquals(5, window.get("logins").getAsLong());
        counts.restore(window);
        counts.add(record("{\"joined\":true,\"complete\":true,\"tcp\":{\"mss_class\":\"standard\"}}"));
        Map<String, Long> again = counts(counts.drain());
        assertEquals(5, again.get("mss_class|standard|unlabelled"));
        assertEquals(1, again.get("mss_class|pppoe|unlabelled"));
        assertEquals(6, again.get("all|all|unlabelled"));
        counts.add(record("{}")); counts.clear();
        assertNull(counts.drain());
    }

    @Test void anInactiveProbeTouchesNothingAndCountingStartsAndStopsWithTheSwitch() throws Exception {
        Path experiments = dir.resolve("experiments");
        ProbeJournal journal = new ProbeJournal(experiments, warning -> fail(warning), false);
        ProbeCounts counts = new ProbeCounts();
        AtomicBoolean contributing = new AtomicBoolean();
        ConnectionProbes probes = new ConnectionProbes(40, line -> fail("only explicit probes log"), journal, false, false, counts, contributing::get);
        InetAddress address = InetAddress.getByName("192.0.2.7");
        UUID player = UUID.randomUUID();
        assertFalse(probes.active());
        probes.handshake("192.0.2.7|1");
        probes.preLogin("192.0.2.7|1", address, new Object(), 774, "mc.example.org");
        probes.joined(player, "Steve", "192.0.2.7|1", address, false, false, new Object());
        assertFalse(probes.probing(player));
        assertFalse(Files.exists(experiments), "nothing is written while the probe is inactive");
        contributing.set(true);
        assertTrue(probes.active());
        for (int i = 0; i < 5; i++) {
            UUID next = UUID.randomUUID();
            probes.preLogin("192.0.2.7|" + i, address, new Object(), 774, "mc.example.org");
            probes.joined(next, "P" + i, "192.0.2.7|" + i, address, false, false, new Object());
            probes.left(next);
        }
        assertEquals(5, counts.size());
        assertTrue(Files.exists(experiments.resolve("key")));
        journal.close();
        try (java.util.stream.Stream<Path> files = Files.list(experiments)) {
            assertFalse(files.anyMatch(f -> f.getFileName().toString().endsWith(".jsonl")), "counting alone writes no journal lines");
        }
        contributing.set(false);
        UUID late = UUID.randomUUID();
        probes.joined(late, "Late", "192.0.2.7|99", address, false, false, new Object());
        assertFalse(probes.probing(late));
        assertTrue(probes.describe().stream().anyMatch(line -> line.startsWith("Counts for Connection Guard Intel: off")));
    }
}
