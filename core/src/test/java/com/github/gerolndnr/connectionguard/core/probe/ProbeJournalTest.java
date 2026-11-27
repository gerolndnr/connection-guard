package com.github.gerolndnr.connectionguard.core.probe;

import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.api.v1.DetectionMetadata;
import com.github.gerolndnr.connectionguard.api.v1.DetectionObservation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProbeJournalTest {
    @TempDir Path dir;

    private List<JsonObject> lines(Path dir) throws IOException {
        Path file = dir.resolve("connection-probe-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
        if (!Files.exists(file)) return Collections.emptyList();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).collect(Collectors.toList());
    }

    private static DecisionObservation denied(String ip) {
        Map<DetectionMetadata.Type, Boolean> types = new EnumMap<>(DetectionMetadata.Type.class);
        types.put(DetectionMetadata.Type.VPN, true); types.put(DetectionMetadata.Type.HOSTING, true); types.put(DetectionMetadata.Type.TOR, false);
        Source source = new Source("proxycheck", Scope.VPN, DetectionObservation.positive(new DetectionMetadata(types, 9009L, "M247", null, "NL", 100, null)), 12, true, false);
        return new DecisionObservation(Platform.VELOCITY, Phase.PRE_AUTHENTICATION, Mode.ENFORCE, IdentityTrust.UNTRUSTED, null, ip, Outcome.DENY,
                Reason.VPN_FLAG, Check.POSITIVE, Check.NOT_CHECKED, 1000, 12, false, EnumSet.of(Flag.VPN), Collections.singletonList(source), Collections.emptyList());
    }

    @Test void hashesAreKeyedStableAndNeverTheValue() throws IOException {
        ProbeJournal journal = new ProbeJournal(dir, warning -> fail(warning));
        String hash = journal.hash("net", "192.0.2.0/24");
        assertEquals(16, hash.length());
        assertEquals(hash, journal.hash("net", "192.0.2.0/24"));
        assertNotEquals(hash, journal.hash("acct", "192.0.2.0/24"));
        // The same key survives a restart; another server's key gives other hashes.
        assertEquals(hash, new ProbeJournal(dir, warning -> fail(warning)).hash("net", "192.0.2.0/24"));
        assertNotEquals(hash, new ProbeJournal(dir.resolve("other"), warning -> fail(warning)).hash("net", "192.0.2.0/24"));
    }

    @Test void accountsAreNewOnlyOnceAcrossRestarts() throws IOException {
        ProbeJournal journal = new ProbeJournal(dir, warning -> fail(warning));
        assertTrue(journal.firstSeen("a1"));
        assertFalse(journal.firstSeen("a1"));
        journal.close();
        ProbeJournal again = new ProbeJournal(dir, warning -> fail(warning));
        assertFalse(again.firstSeen("a1"));
        assertTrue(again.firstSeen("b2"));
        assertEquals(0, again.daysRunning());
    }

    @Test void oldDaysArePurged() throws IOException {
        Path old = dir.resolve("connection-probe-" + LocalDate.now(ZoneOffset.UTC).minusDays(ProbeJournal.RETENTION_DAYS + 1) + ".jsonl");
        Path recent = dir.resolve("connection-probe-" + LocalDate.now(ZoneOffset.UTC).minusDays(1) + ".jsonl");
        Files.createDirectories(dir);
        Files.write(old, "{}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(recent, "{}\n".getBytes(StandardCharsets.UTF_8));
        new ProbeJournal(dir, warning -> fail(warning));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
    }

    @Test void aJoinedLoginBecomesOneLineWithoutAddressAccountOrSkin() throws IOException {
        ProbeJournal journal = new ProbeJournal(dir, warning -> fail(warning));
        ConnectionProbes probes = new ConnectionProbes(40, line -> { }, journal, false);
        InetAddress address = InetAddress.getByName("192.0.2.7");
        UUID player = UUID.fromString("00000000-0000-4000-8000-000000000001");
        probes.onDecision(denied("192.0.2.7")); // the label, even if the player later joins through another route
        probes.handshake("192.0.2.7|50000");
        probes.preLogin("192.0.2.7|50000", address, new Object(), 767, "play.example.org");
        probes.joined(player, "Steve", "192.0.2.7|50000", address, false, false, new Object());
        probes.sample(player, 30, new Object(), false);
        probes.sample(player, 25, new Object(), true, new ConnectionProbes.ClientInfo("vanilla", "de_de", null, "http://textures.minecraft.net/texture/abc"));
        journal.close();

        List<JsonObject> lines = lines(dir);
        assertEquals(1, lines.size());
        JsonObject line = lines.get(0);
        String text = line.toString();
        assertFalse(text.contains("192.0.2"), text);
        assertFalse(text.contains("Steve") || text.contains(player.toString()) || text.contains("textures.minecraft.net") || text.contains("example.org"), text);
        assertTrue(line.get("joined").getAsBoolean());
        assertTrue(line.get("complete").getAsBoolean());
        assertTrue(line.get("new_acct").getAsBoolean());
        assertEquals(4, line.get("family").getAsInt());
        assertEquals(journal.hash("net", "192.0.2.0/24"), line.get("net").getAsString());
        assertFalse(line.has("tcp")); // no epoll channel in tests
        assertEquals(2, line.getAsJsonObject("ping").get("n").getAsInt());
        assertEquals(25, line.getAsJsonObject("ping").get("min_ms").getAsLong());
        JsonObject client = line.getAsJsonObject("client");
        assertEquals(767, client.get("protocol").getAsInt());
        assertEquals("name", client.get("vhost").getAsString());
        assertEquals("vanilla", client.get("brand").getAsString());
        assertEquals("de_de", client.get("locale").getAsString());
        assertEquals(16, client.get("skin").getAsString().length());
        assertTrue(client.has("handshake_to_login_ms") && client.has("login_to_join_ms"));
        JsonObject verdict = line.getAsJsonObject("verdict");
        assertEquals("DENY", verdict.get("outcome").getAsString());
        assertEquals("VPN_FLAG", verdict.get("reason").getAsString());
        assertEquals(9009, verdict.get("asn").getAsLong());
        assertEquals("NL", verdict.get("country").getAsString());
        JsonObject source = verdict.getAsJsonArray("sources").get(0).getAsJsonObject();
        assertEquals("POSITIVE", source.get("status").getAsString());
        assertEquals("[\"VPN\",\"HOSTING\"]", source.getAsJsonArray("types").toString());
    }

    @Test void loginsThatNeverJoinAreWrittenOnceTheyExpire() throws IOException {
        ProbeJournal journal = new ProbeJournal(dir, warning -> fail(warning));
        ConnectionProbes probes = new ConnectionProbes(40, line -> { }, journal, false);
        probes.preLogin("2001:db8:0:0:0:0:0:9|50000", InetAddress.getByName("2001:db8::9"), null, 767, "203.0.113.1");
        probes.onDecision(denied("2001:db8::9"));
        probes.expire(); // too young
        assertTrue(probes.describe().get(1).contains("never-joined=0"));
        expireAll(probes);
        journal.close();
        List<JsonObject> lines = lines(dir);
        assertEquals(1, lines.size());
        JsonObject line = lines.get(0);
        assertFalse(line.get("joined").getAsBoolean());
        assertFalse(line.has("acct"));
        assertEquals(6, line.get("family").getAsInt());
        assertEquals(journal.hash("net", "2001:db8:0::/48"), line.get("net").getAsString());
        assertEquals("ip", line.getAsJsonObject("client").get("vhost").getAsString());
        assertEquals("DENY", line.getAsJsonObject("verdict").get("outcome").getAsString());
    }

    @Test void aLeavingPlayerIsWrittenIncompleteAndOnlyOnce() throws IOException {
        ProbeJournal journal = new ProbeJournal(dir, warning -> fail(warning));
        ConnectionProbes probes = new ConnectionProbes(40, line -> { }, journal, false);
        InetAddress address = InetAddress.getByName("198.51.100.4");
        UUID player = UUID.randomUUID();
        probes.preLogin("198.51.100.4|1", address, null, 767, null);
        probes.joined(player, "Alex", "198.51.100.4|1", address, false, true, new Object());
        probes.left(player, new ConnectionProbes.ClientInfo(null, null, "ANDROID", null));
        probes.left(player);
        probes.sample(player, 20, new Object(), true);
        journal.close();
        List<JsonObject> lines = lines(dir);
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).get("complete").getAsBoolean());
        assertTrue(lines.get(0).get("behind_tcp_proxy").getAsBoolean());
        assertEquals("ANDROID", lines.get(0).getAsJsonObject("client").get("bedrock_os").getAsString());
        assertFalse(lines.get(0).getAsJsonObject("client").has("vhost"));
    }

    @Test void helpersKeepOnlyCoarseValues() throws IOException {
        assertEquals("192.0.2.0/24", ConnectionProbes.prefix(InetAddress.getByName("192.0.2.200")));
        assertEquals("2001:db8:ab::/48", ConnectionProbes.prefix(InetAddress.getByName("2001:db8:ab:cd::1")));
        assertEquals("ip", ConnectionProbes.hostKind("203.0.113.9"));
        assertEquals("ip", ConnectionProbes.hostKind("2001:db8::1"));
        assertEquals("name", ConnectionProbes.hostKind("mc.example.org."));
        assertNull(ConnectionProbes.hostKind(""));
        assertNull(ConnectionProbes.literal("example.org")); // never a DNS query
        assertNull(ConnectionProbes.literal("1234"));
        assertEquals("abc?d", ConnectionProbes.clip("abcéd", 10));
        assertEquals("abc", ConnectionProbes.clip("abcdef", 3));
    }

    private static void expireAll(ConnectionProbes probes) { probes.expire(System.currentTimeMillis() + ConnectionProbes.PENDING_MILLIS + 1); }
}
