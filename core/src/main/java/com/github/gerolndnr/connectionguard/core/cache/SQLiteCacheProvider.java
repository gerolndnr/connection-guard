package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.sql.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Schema v2 leaves legacy tables untouched and never imports old boolean-only decisions. */
public class SQLiteCacheProvider extends SerialCacheProvider {
    // Avoid javac 8 bridge debug-table differences between full and incremental builds.
    @Override public void setNamespace(String namespace) { super.setNamespace(namespace); }
    private final String location;
    private Connection connection;
    public SQLiteCacheProvider(String location) { this.location = location; }
    @Override public CompletableFuture<Boolean> setup() { return submit(() -> {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + location);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=1000");
            statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_cache_v2 (cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, expires INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS cg_v2_expiry ON connectionguard_cache_v2(expires)");
            statement.execute("DELETE FROM connectionguard_cache_v2 WHERE expires < " + System.currentTimeMillis());
        }
        return true;
    }); }
    @Override public CompletableFuture<Boolean> disband() { return stop(() -> { if (connection != null) connection.close(); return true; }); }
    private String read(String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT payload FROM connectionguard_cache_v2 WHERE cache_key=? AND expires>?")) {
            query.setString(1, key); query.setLong(2, System.currentTimeMillis());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }
    private void write(String key, String payload, long ttl) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("INSERT OR REPLACE INTO connectionguard_cache_v2 VALUES (?, ?, ?)")) {
            query.setString(1, key); query.setString(2, payload); query.setLong(3, System.currentTimeMillis() + ttl); query.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("DELETE FROM connectionguard_cache_v2 WHERE expires<?")) {
            query.setLong(1, System.currentTimeMillis()); query.executeUpdate();
        }
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) {
        final String key = key("vpn", ip); final long ttl = ConnectionGuard.getVpnCacheExpirationTime() * 60000L;
        return submit(() -> CacheCodec.vpn(read(key), ip, ttl));
    }
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip) {
        final String key = key("geo", ip); final long ttl = ConnectionGuard.getGeoCacheExpirationTime() * 60000L;
        return submit(() -> CacheCodec.geo(read(key), ip, ttl));
    }
    @Override public CompletableFuture<Void> addVpnResult(VpnResult result) {
        final String key = key("vpn", result.getIpAddress()); result.setCachedOn(System.currentTimeMillis());
        final String payload = CacheCodec.encode(result); final long ttl = ConnectionGuard.getVpnCacheExpirationTime() * 60000L;
        return submit(() -> { write(key, payload, ttl); return null; });
    }
    @Override public CompletableFuture<Void> addGeoResult(GeoResult result) {
        final String key = key("geo", result.getIpAddress()); result.setCachedOn(System.currentTimeMillis());
        final String payload = CacheCodec.encode(result); final long ttl = ConnectionGuard.getGeoCacheExpirationTime() * 60000L;
        return submit(() -> { write(key, payload, ttl); return null; });
    }
    private CompletableFuture<Boolean> remove(String key) { return submit(() -> {
        try (PreparedStatement query = connection.prepareStatement("DELETE FROM connectionguard_cache_v2 WHERE cache_key=?")) {
            query.setString(1, key); query.executeUpdate(); return true;
        }
    }); }
    @Override public CompletableFuture<Boolean> removeVpnResult(String ip) { return remove(key("vpn", ip)); }
    @Override public CompletableFuture<Boolean> removeGeoResult(String ip) { return remove(key("geo", ip)); }
    private CompletableFuture<Boolean> clear(String type) {
        final String prefix = namespace.replace("_", "\\_") + ":" + type + ":%";
        return submit(() -> {
            try (PreparedStatement query = connection.prepareStatement("DELETE FROM connectionguard_cache_v2 WHERE cache_key LIKE ? ESCAPE '\\'")) {
                query.setString(1, prefix); query.executeUpdate(); return true;
            }
        });
    }
    @Override public CompletableFuture<Boolean> removeAllVpnResults() { return clear("vpn"); }
    @Override public CompletableFuture<Boolean> removeAllGeoResults() { return clear("geo"); }
}
