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
        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedStatement preparedStatement = connection.prepareStatement(
                        "SELECT vpn, cached_on FROM connectionguard_vpn_cache WHERE address=?"
                );
                preparedStatement.setString(1, ipAddress);
                ResultSet resultSet = preparedStatement.executeQuery();

                if (resultSet.next()) {
                    boolean isVpn = resultSet.getBoolean("vpn");
                    long cachedOn = resultSet.getLong("cached_on");

                    // Check if cache data is expired
                    if ((cachedOn + ConnectionGuard.getVpnCacheExpirationTime() * 60 * 1000) > new Date().getTime()) {
                        // Data is not expired.
                        return Optional.of(new VpnResult(ipAddress, isVpn));
                    } else {
                        // Data is expired and needs to be removed.
                        PreparedStatement deleteEntryStatement = connection.prepareStatement(
                                "DELETE FROM connectionguard_vpn_cache WHERE address=?"
                        );
                        deleteEntryStatement.setString(1, ipAddress);
                        deleteEntryStatement.execute();
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedStatement preparedStatement = connection.prepareStatement(
                        "SELECT country_name, city_name, isp_name, cached_on FROM connectionguard_geo_cache WHERE address=?"
                );
                preparedStatement.setString(1, ipAddress);
                ResultSet resultSet = preparedStatement.executeQuery();

                if (resultSet.next()) {
                    String countryName = resultSet.getString("country_name");
                    String cityName = resultSet.getString("city_name");
                    String ispName = resultSet.getString("isp_name");
                    long cachedOn = resultSet.getLong("cached_on");

                    // Check if cache data is expired
                    if ((cachedOn + ConnectionGuard.getGeoCacheExpirationTime() * 60 * 1000) > new Date().getTime()) {
                        // Data is not expired.
                        return Optional.of(new GeoResult(ipAddress, countryName, cityName, ispName));
                    } else {
                        // Data is expired and needs to be removed.
                        PreparedStatement removeEntryStatement = connection.prepareStatement(
                                "DELETE FROM connectionguard_geo_cache WHERE address=?"
                        );
                        removeEntryStatement.setString(1, ipAddress);
                        removeEntryStatement.execute();
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return Optional.empty();
            }
        });
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedStatement preparedStatement = connection.prepareStatement(
                        "DELETE FROM connectionguard_vpn_cache WHERE address=?"
                );
                preparedStatement.setString(1, ipAddress);
                preparedStatement.execute();
                return true;
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removeGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedStatement preparedStatement = connection.prepareStatement(
                        "DELETE FROM connectionguard_geo_cache WHERE address=?"
                );
                preparedStatement.setString(1, ipAddress);
                preparedStatement.execute();
                return true;
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removeAllVpnResults() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Statement statement = connection.createStatement();
                statement.execute("DELETE FROM connectionguard_vpn_cache");
                return true;
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removeAllGeoResults() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Statement statement = connection.createStatement();
                statement.execute("DELETE FROM connectionguard_geo_cache");
                return true;
            } catch (SQLException e) {
                ConnectionGuard.getLogger().info("SQLite | " + e.getMessage());
                return false;
            }
        });
    }
}
