package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class SQLiteCacheProviderTest {
    @TempDir Path directory;
    SQLiteCacheProvider cache;
    @BeforeEach void setup() throws Exception {
        cache = new SQLiteCacheProvider(directory.resolve("cache.db").toString());
        cache.setNamespace("policy_A"); assertTrue(cache.setup().get());
        ConnectionGuard.setVpnCacheExpirationTime(1440); ConnectionGuard.setGeoCacheExpirationTime(4320);
    }
    @AfterEach void close() throws Exception { cache.disband().get(); }
    VpnResult value() {
        VpnResult result = new VpnResult("192.0.2.1", true, Optional.of("Fixture operator"));
        result.setVotes(Collections.singletonList(new ProviderVote("fixture", ProviderVote.Status.POSITIVE, FailureReason.NONE, 4)));
        return result;
    }
    @Test void preservesSourceTraceAndAgeWithOneRowPerAddress() throws Exception {
        cache.addVpnResult(value()).get(); cache.addVpnResult(value()).get();
        VpnResult loaded = cache.getVpnResult("192.0.2.1").get().get();
        assertTrue(loaded.isVpn()); assertEquals("Fixture operator", loaded.getVpnProviderName().get());
        assertEquals(1, loaded.getVotes().size()); assertTrue(loaded.getCachedOn() > 0);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cache.db")); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM connectionguard_cache_v2")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
    }
    @Test void policyNamespacesCannotReadOrClearEachOthersEntries() throws Exception {
        cache.addVpnResult(value()).get(); cache.setNamespace("policyXA");
        assertFalse(cache.getVpnResult("192.0.2.1").get().isPresent());
        cache.addVpnResult(value()).get(); cache.setNamespace("policy_A");
        cache.removeAllVpnResults().get(); assertFalse(cache.getVpnResult("192.0.2.1").get().isPresent());
        cache.setNamespace("policyXA"); assertTrue(cache.getVpnResult("192.0.2.1").get().isPresent());
    }
    @Test void geoAndVpnAreSeparateAndExpiredRowsAreNotReused() throws Exception {
        cache.addVpnResult(value()).get(); cache.addGeoResult(new GeoResult("192.0.2.1", "ZZ", "Fixture", "ISP")).get();
        cache.removeVpnResult("192.0.2.1").get(); assertTrue(cache.getGeoResult("192.0.2.1").get().isPresent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cache.db")); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE connectionguard_cache_v2 SET expires=1");
        }
        assertFalse(cache.getGeoResult("192.0.2.1").get().isPresent());
    }
    @Test void unknownInconsistentAndExpiredPayloadsAreRejected() {
        VpnResult result = value(); result.setCachedOn(System.currentTimeMillis() - 10000);
        assertFalse(CacheCodec.vpn(CacheCodec.encode(result), "192.0.2.1", 1000).isPresent());
        result.setCachedOn(System.currentTimeMillis()); result.setStatus(ProviderVote.Status.UNKNOWN);
        assertFalse(CacheCodec.vpn(CacheCodec.encode(result), "192.0.2.1", 1000).isPresent());
        assertFalse(CacheCodec.vpn("{broken", "192.0.2.1", 1000).isPresent());
    }
}
