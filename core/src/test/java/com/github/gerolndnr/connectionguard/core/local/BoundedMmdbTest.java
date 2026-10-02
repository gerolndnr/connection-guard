package com.github.gerolndnr.connectionguard.core.local;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedMmdbTest {
    static byte[] fixture(String file) throws IOException {
        try (InputStream input = BoundedMmdbTest.class.getResourceAsStream("/mmdb/" + file)) {
            assertNotNull(input); return LocalDataStore.readBounded(input, 1048576);
        }
    }
    @Test void officialCountryAndAsnRecordsRoundTripForNativeAndMappedAddresses() throws Exception {
        BoundedMmdb country = new BoundedMmdb(fixture("GeoIP2-Country-Test.mmdb"));
        assertEquals("GeoIP2-Country", country.getDatabaseType());
        assertEquals("JP", ((Map<?, ?>) country.lookup("2001:218::1").get("country")).get("iso_code"));
        assertEquals("GB", ((Map<?, ?>) country.lookup("81.2.69.160").get("country")).get("iso_code"));
        BoundedMmdb asn = new BoundedMmdb(fixture("GeoLite2-ASN-Test.mmdb"));
        assertEquals(15169L, asn.lookup("1.0.0.1").get("autonomous_system_number"));
        assertEquals("Google Inc.", asn.lookup("::ffff:1.0.0.1").get("autonomous_system_organization"));
        assertTrue(asn.lookup("192.0.2.1").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> asn.lookup("1.0.0.1").put("modified", true));
    }
    @Test void hostilePointerFanoutAndPayloadAmplificationAreRejectedWithinResourceBounds() throws Exception {
        for (String file : new String[]{"MaxMind-DB-test-pointer-decoder-dos-ipv6.mmdb", "MaxMind-DB-test-payload-amplification-dos-worst-case.mmdb"}) {
            byte[] bytes = fixture(file);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                BoundedMmdb reader = new BoundedMmdb(bytes);
                assertThrows(IllegalArgumentException.class, () -> reader.lookup("1.2.3.4"));
            });
        }
    }
    @Test void invalidTreePointersAndTruncatedFilesAreRejectedAtOpen() throws Exception {
        byte[] original = fixture("GeoIP2-Country-Test.mmdb");
        byte[] badTree = original.clone(); Arrays.fill(badTree, 0, 8, (byte) 0xff);
        assertThrows(IllegalArgumentException.class, () -> new BoundedMmdb(badTree));
        assertThrows(IllegalArgumentException.class, () -> new BoundedMmdb(Arrays.copyOf(original, 32)));
        assertThrows(IllegalArgumentException.class, () -> new BoundedMmdb(new byte[0]));
    }
    @Test void pinnedFixturesHaveRecordedHashesAndLicenseAttribution() throws Exception {
        JsonObject manifest = JsonParser.parseString(new String(fixture("manifest.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(manifest.get("sourceCommit").getAsString().matches("[0-9a-f]{40}"));
        assertEquals("MIT", manifest.get("license").getAsString());
        for (JsonElement element : manifest.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject(); byte[] bytes = fixture(file.get("file").getAsString());
            assertEquals(file.get("bytes").getAsInt(), bytes.length);
            assertEquals(file.get("sha256").getAsString(), LocalSource.hash(bytes));
        }
    }
}
