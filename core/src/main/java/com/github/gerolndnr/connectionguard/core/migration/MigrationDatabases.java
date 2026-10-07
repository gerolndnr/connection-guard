package com.github.gerolndnr.connectionguard.core.migration;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;

/** Fixed local, read-only JDBC queries. Never reuse a competitor's URL or remote credentials. */
public final class MigrationDatabases {
    private static volatile Consumer<String> loader;
    private MigrationDatabases() { }
    /** Native Libby callback; only known sqlite/h2 coordinates may be loaded. */
    public static void setLoader(Consumer<String> callback) { loader = callback; }
    static Connection open(String driver, String url, Properties properties) throws Exception {
        String id = driver.equals("org.h2.Driver") ? "h2" : "sqlite";
        if (loader != null) loader.accept(id);
        Driver jdbc = (Driver) Class.forName(driver, true, MigrationDatabases.class.getClassLoader()).getDeclaredConstructor().newInstance();
        Connection connection = jdbc.connect(url, properties);
        if (connection == null) throw new SQLException("Unsupported migration driver.");
        return connection;
    }
    static void sqlite(CompetitorImport plan, boolean advanced) throws IOException {
        String name = "database.db"; plan.track(name); Path path = plan.directory.resolve(name);
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            plan.track(name + suffix);
            if (Files.exists(plan.directory.resolve(name + suffix), LinkOption.NOFOLLOW_LINKS)) {
                plan.blockers.add("Stop the competitor cleanly before importing SQLite admin lists; a WAL/journal/SHM is present."); return;
            }
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { plan.warnings.add("No local " + plan.source + " admin database found; no cached verdict is imported."); return; }
        try (Connection db = open("org.sqlite.JDBC", "jdbc:sqlite:" + path.toAbsolutePath().toUri().toASCIIString() + "?mode=ro", new Properties())) {
            try (Statement query = db.createStatement()) { query.execute("PRAGMA query_only=ON"); }
            db.setAutoCommit(false);
            Set<String> tables = new HashSet<>();
            try (Statement query = db.createStatement(); ResultSet rows = query.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            if (advanced) {
                if (!tables.contains("aavpn_whitelist") || !tables.contains("aavpn_blacklist")) throw new SQLException("Unknown admin schema.");
                for (String[] column : new String[][]{{"aavpn_whitelist","ip","ALLOW"},{"aavpn_blacklist","ip","DENY"},
                        {"aavpn_uuid_whitelist","uuid","ALLOW"},{"aavpn_uuid_blacklist","uuid","DENY"},
                        {"aavpn_wildcard_whitelist","pattern","ALLOW"},{"aavpn_wildcard_blacklist","pattern","DENY"}})
                    if (tables.contains(column[0])) read(db, plan, column[0], column[1], AccessRule.Effect.valueOf(column[2]));
            } else {
                if (!tables.contains("vpn_whitelist_ips") || !tables.contains("vpn_whitelist_names")) throw new SQLException("Unknown admin schema.");
                read(db, plan, "vpn_whitelist_ips", "ip", AccessRule.Effect.ALLOW);
                read(db, plan, "vpn_whitelist_names", "uuid", AccessRule.Effect.ALLOW);
            }
            db.rollback();
            if (!plan.fingerprints.get(name).equals(MigrationFiles.fingerprint(path))) throw new IOException("Source database changed during preview; retry after stopping the competitor.");
        } catch (Exception unavailable) {
            plan.blockers.add("Local SQLite admin lists could not be read safely. Check driver availability/schema and stop the competitor; values redacted.");
        }
    }
    static void h2(CompetitorImport plan) throws IOException {
        String name = "databases/database.mv.db"; plan.track(name); plan.track("databases/database.lock.db");
        Path path = plan.directory.resolve(name);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { plan.warnings.add("No local KauriVPN H2 admin database found."); return; }
        if (Files.exists(plan.directory.resolve("databases/database.lock.db"), LinkOption.NOFOLLOW_LINKS)) {
            plan.blockers.add("Stop KauriVPN before importing its locked H2 database."); return;
        }
        Path base = plan.directory.resolve("databases/database").toAbsolutePath();
        if (base.toString().indexOf(';') >= 0 || base.toString().chars().anyMatch(Character::isISOControl)) throw new IOException("Unsupported H2 path (value redacted).");
        Properties properties = new Properties();
        properties.setProperty("user", plan.string("database.username", "root"));
        properties.setProperty("password", plan.string("database.password", "password"));
        try (Connection db = open("org.h2.Driver", "jdbc:h2:file:" + base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO", properties)) {
            Set<String> tables = new HashSet<>();
            try (ResultSet rows = db.getMetaData().getTables(null, "PUBLIC", null, new String[]{"BASE TABLE", "TABLE"})) {
                while (rows.next()) tables.add(rows.getString("TABLE_NAME"));
            }
            if (!tables.contains("whitelisted")) throw new SQLException("Unknown admin schema.");
            read(db, plan, "whitelisted", "uuid", AccessRule.Effect.ALLOW);
            if (tables.contains("whitelisted-ranges")) read(db, plan, "whitelisted-ranges", "cidr_string", AccessRule.Effect.ALLOW);
            else if (tables.contains("whitelisted-ips")) read(db, plan, "whitelisted-ips", "ip", AccessRule.Effect.ALLOW);
            else throw new SQLException("Unknown range schema.");
            if (!plan.fingerprints.get(name).equals(MigrationFiles.fingerprint(path))) throw new IOException("Source H2 database changed.");
        } catch (Exception unavailable) {
            plan.blockers.add("KauriVPN H2 admin lists could not be read safely; use Java 11+ with a compatible H2 database, or export admin rules locally. No rule is silently discarded.");
        }
    }
    private static void read(Connection db, CompetitorImport plan, String table, String column, AccessRule.Effect effect) throws SQLException {
        // Table and column come only from the fixed schema declarations above, never from config/data.
        try (Statement query = db.createStatement()) {
            query.setQueryTimeout(2); query.setMaxRows(513);
            try (ResultSet rows = query.executeQuery("SELECT \"" + column + "\" FROM \"" + table + "\"")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 512) throw new SQLException("Too many admin rules.");
                    String selector = rows.getString(1);
                    if (selector == null || selector.length() > 200) throw new SQLException("Invalid admin selector.");
                    plan.selector(selector, effect, AccessRule.Scope.ALL, table);
                }
            }
        }
    }
}
