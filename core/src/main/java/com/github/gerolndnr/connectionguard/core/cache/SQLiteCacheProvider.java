package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import java.sql.*;
import java.util.Date;
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
                statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_geo_cache (address TEXT, country_name TEXT, city_name TEXT, isp_name TEXT, cached_on INTEGER);");
                statement.execute("CREATE INDEX IF NOT EXISTS vpn_address ON connectionguard_vpn_cache (address)");
                statement.execute("CREATE INDEX IF NOT EXISTS geo_address ON connectionguard_geo_cache (address)");
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
        return CompletableFuture.runAsync(() -> {
            try {
                PreparedStatement preparedStatement = connection.prepareStatement(
                        "INSERT INTO connectionguard_vpn_cache (address, vpn, cached_on) VALUES (?, ?, ?)"
                );

                preparedStatement.setString(1, vpnResult.getIpAddress());
                preparedStatement.setBoolean(2, vpnResult.isVpn());
                preparedStatement.setLong(3, new Date().getTime());

                preparedStatement.execute();
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> addGeoResult(GeoResult geoResult) {
        return CompletableFuture.runAsync(() -> {
            try {
                PreparedStatement preparedStatement = connection.prepareStatement(
                        "INSERT INTO connectionguard_geo_cache (address, country_name, city_name, isp_name, cached_on) VALUES (?, ?, ?, ?, ?)"
                );

                preparedStatement.setString(1, geoResult.getIpAddress());
                preparedStatement.setString(2, geoResult.getCountryName());
                preparedStatement.setString(3, geoResult.getCityName());
                preparedStatement.setString(4, geoResult.getIspName());
                preparedStatement.setLong(5, new Date().getTime());

                preparedStatement.execute();
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
            }
        });
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
