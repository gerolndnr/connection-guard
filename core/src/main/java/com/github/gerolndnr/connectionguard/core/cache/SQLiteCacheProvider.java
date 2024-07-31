package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class SQLiteCacheProvider implements CacheProvider {
    private Connection connection;
    private String databaseFileLocation;

    public SQLiteCacheProvider(String databaseFileLocation) {
        this.databaseFileLocation = databaseFileLocation;
    }

    @Override
    public CompletableFuture<Boolean> setup() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFileLocation);

                Statement statement = connection.createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_vpn_cache (address TEXT, vpn BOOLEAN, cached_on INTEGER);");
                statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_geo_cache (address TEXT, country_name TEXT, city_name TEXT, isp_name TEXT);");
                return true;
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> disband() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                connection.close();
                return true;
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return null;
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return null;
    }

    @Override
    public CompletableFuture<Void> addVpnResult(VpnResult vpnResult) {
        return null;
    }

    @Override
    public CompletableFuture<Void> addGeoResult(GeoResult geoResult) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> removeVpnResult(String ipAddress) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> removeGeoResult(String ipAddress) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> removeAllVpnResults() {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> removeAllGeoResults() {
        return null;
    }
}
