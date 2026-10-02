package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalDataStoreTest {
    @TempDir Path directory;
    static LocalSource source(String id, LocalSource.Kind kind) { return new LocalSource(id, kind, "Synthetic fixture", "MIT", "Connection Guard contributors", 24, ""); }
    private LocalDataStore store(LocalSource source) throws Exception {
        LocalDataStore store = new LocalDataStore(directory.toRealPath(), Collections.singletonList(source)); store.prepareInbox(); return store;
    }
    private void inbox(String name, byte[] bytes) throws Exception { Files.write(directory.resolve("local-data/inbox/" + name), bytes); }
    @Test void listMembershipIsPositiveButAbsenceRemainsUnknownAndHostingOnlyEnriches() throws Exception {
        long now = System.currentTimeMillis();
        for (LocalSource.Kind kind : new LocalSource.Kind[]{LocalSource.Kind.VPN, LocalSource.Kind.TOR, LocalSource.Kind.HOSTING}) {
            LocalSource source = source(kind.name().toLowerCase(Locale.ROOT), kind); LocalDataStore store = store(source);
            inbox("list.txt", "192.0.2.0/24\n2001:db8::/32\n".getBytes(StandardCharsets.UTF_8));
            LocalSnapshot snapshot = store.importFile(source.id, "list.txt", now - 1000, now);
            VpnResult matched = snapshot.vpn("::ffff:192.0.2.1", now);
            assertEquals(kind == LocalSource.Kind.HOSTING ? ProviderVote.Status.UNKNOWN : ProviderVote.Status.POSITIVE, matched.getStatus());
            assertEquals(Boolean.TRUE, matched.getDetails().get(DetectionDetails.Type.valueOf(kind.name())));
            VpnResult absent = snapshot.vpn("198.51.100.1", now);
            assertEquals(ProviderVote.Status.UNKNOWN, absent.getStatus()); assertEquals(FailureReason.NO_EVIDENCE, absent.getSourceReason());
            assertNull(absent.getDetails().get(DetectionDetails.Type.valueOf(kind.name())));
            assertEquals(snapshot.version, store.load(source.id, now).version);
            assertTrue(snapshot.describe(now).contains("ipv6Intervals=1"));
        }
    }
    @Test void expirationRemovesAllEvidenceAndReimportDoesNotChangeAnExplicitAsOf() throws Exception {
        LocalDataStore store = store(source("vpn", LocalSource.Kind.VPN)); long now = System.currentTimeMillis();
        inbox("list.txt", "192.0.2.0/24\n".getBytes(StandardCharsets.UTF_8));
        LocalSnapshot first = store.importFile("vpn", "list.txt", now - 1000, now);
        assertEquals(ProviderVote.Status.POSITIVE, first.vpn("192.0.2.1", first.validUntil() - 1).getStatus());
        VpnResult expired = first.vpn("192.0.2.1", first.validUntil());
        assertEquals(FailureReason.STALE_DATA, expired.getSourceReason()); assertTrue(expired.getDetails().getClassifications().isEmpty());
        LocalSnapshot copied = store.importFile("vpn", "list.txt", now - 1000, now + 1000);
        assertEquals(first.dataTime, copied.dataTime); assertEquals(first.validUntil(), copied.validUntil());
    }
    @Test void failedUpdateRetainsManifestAndImmutableOldGeneration() throws Exception {
        LocalDataStore store = store(source("vpn", LocalSource.Kind.VPN)); long now = System.currentTimeMillis();
        inbox("list.txt", "192.0.2.0/24\n".getBytes(StandardCharsets.UTF_8)); LocalSnapshot old = store.importFile("vpn", "list.txt", now, now);
        byte[] manifest = Files.readAllBytes(directory.resolve("local-data/vpn.json"));
        for (String invalid : new String[]{"", "not-an-address\n", "# only a comment\n", "192.0.2.1/99\n", "192.0.2.1\n" + String.join("", Collections.nCopies(257, "#"))}) {
            inbox("bad.txt", invalid.getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> store.importFile("vpn", "bad.txt", now, now));
            assertArrayEquals(manifest, Files.readAllBytes(directory.resolve("local-data/vpn.json")));
        }
        inbox("next.txt", "198.51.100.0/24\n".getBytes(StandardCharsets.UTF_8)); LocalSnapshot next = store.importFile("vpn", "next.txt", now, now);
        assertNotEquals(old.version, next.version); assertEquals(old.version, old.vpn("192.0.2.1", now).getSourceVersion());
        assertEquals(ProviderVote.Status.POSITIVE, old.vpn("192.0.2.1", now).getStatus());
        assertEquals(ProviderVote.Status.UNKNOWN, next.vpn("192.0.2.1", now).getStatus());
        try (java.util.stream.Stream<Path> files = Files.list(directory.resolve("local-data"))) { assertEquals(1, files.filter(file -> file.toString().endsWith(".data")).count()); }
    }
    @Test void pathsSizesHashAndAttributionAreValidatedWithoutSecretErrors() throws Exception {
        LocalSource source = source("vpn", LocalSource.Kind.VPN); LocalDataStore store = store(source); long now = System.currentTimeMillis();
        assertThrows(IllegalArgumentException.class, () -> store.importFile("vpn", "../secret.txt", now, now));
        inbox("large.txt", new byte[4 * 1024 * 1024 + 1]);
        assertThrows(java.io.IOException.class, () -> store.importFile("vpn", "large.txt", now, now));
        inbox("list.txt", "192.0.2.0/24\n".getBytes(StandardCharsets.UTF_8)); LocalSnapshot valid = store.importFile("vpn", "list.txt", now, now);
        Files.write(directory.resolve("local-data/vpn." + valid.version + ".data"), "198.51.100.1\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> store.load("vpn", now));
        for (String endpoint : new String[]{"http://example.invalid/list", "https://example.invalid/list?key=fake-secret", "https://user:fake-secret@example.invalid/list"}) {
            Exception invalid = assertThrows(IllegalArgumentException.class, () -> new LocalSource("vpn", LocalSource.Kind.VPN, "Fixture", "MIT", "Fixture", 24, endpoint));
            assertFalse(invalid.getMessage().contains("fake-secret"));
        }
        assertThrows(IllegalArgumentException.class, () -> new LocalSource("geo", LocalSource.Kind.GEO, "Fixture", "MIT", "Fixture", 24, "https://example.invalid/db"));
        Path external = directory.resolve("external.txt"); Files.write(external, "192.0.2.1".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(directory.resolve("local-data/inbox/linked.txt"), external);
        assertThrows(IllegalArgumentException.class, () -> store.importFile("vpn", "linked.txt", now, now));
    }
    @Test void mmdbAgeUsesEmbeddedBuildTimeAndWrongTypeCannotReplaceData() throws Exception {
        LocalSource geo = new LocalSource("geo", LocalSource.Kind.GEO, "MaxMind test data", "MIT", "Copyright MaxMind Inc.", 87600, "");
        LocalDataStore store = store(geo); long now = System.currentTimeMillis();
        inbox("country.mmdb", BoundedMmdbTest.fixture("GeoIP2-Country-Test.mmdb"));
        LocalSnapshot snapshot = store.importFile("geo", "country.mmdb", now, now);
        long built = new BoundedMmdb(BoundedMmdbTest.fixture("GeoIP2-Country-Test.mmdb")).getBuildTime();
        assertEquals(built, snapshot.dataTime); assertEquals("MMDB_BUILD", snapshot.timeBasis);
        assertEquals("JP", snapshot.geo("2001:218::1", built + 1000).get().getCountryName());
        assertFalse(snapshot.geo("2001:218::1", snapshot.validUntil()).isPresent());
        inbox("asn.mmdb", BoundedMmdbTest.fixture("GeoLite2-ASN-Test.mmdb"));
        assertThrows(IllegalArgumentException.class, () -> store.importFile("geo", "asn.mmdb", now, now));
        assertEquals(snapshot.version, store.load("geo", now).version);
    }
    @Test void manifestSchemaAndDatesCannotBeImplicitlyCoercedAndFreshnessChangesDoNotRenewDates() throws Exception {
        LocalSource original = source("vpn", LocalSource.Kind.VPN); LocalDataStore store = store(original); long now = System.currentTimeMillis();
        inbox("list.txt", "192.0.2.0/24\n".getBytes(StandardCharsets.UTF_8)); LocalSnapshot snapshot = store.importFile("vpn", "list.txt", now - 1000, now);
        Path path = directory.resolve("local-data/vpn.json"); String valid = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        for (String invalid : new String[]{valid.replace("\"schema\":1,", ""), valid.replace("\"schema\":1", "\"schema\":1.5"), valid.replace("\"bytes\":", "\"unknown\":"), valid.replace("\"dataTime\":" + (now - 1000), "\"dataTime\":\"" + (now - 1000) + "\"")}) {
            Files.write(path, invalid.getBytes(StandardCharsets.UTF_8)); assertThrows(java.io.IOException.class, () -> store.load("vpn", now));
        }
        Files.write(path, valid.getBytes(StandardCharsets.UTF_8));
        LocalSource shorter = new LocalSource(original.id, original.kind, original.source, original.license, original.notice, 1, "");
        LocalSnapshot changed = new LocalDataStore(directory.toRealPath(), Collections.singletonList(shorter)).load("vpn", now);
        assertEquals(snapshot.dataTime, changed.dataTime); assertEquals(snapshot.version, changed.version); assertEquals(snapshot.dataTime + 3600000, changed.validUntil());
    }
}
