package com.github.gerolndnr.connectionguard.core.migration;

import com.github.gerolndnr.connectionguard.core.rules.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored synthetic configs/admin databases, not redistributed competitor artifacts. */
class CompetitorMigrationTest {
    @TempDir Path temporary;
    Path plugins, target;
    @BeforeEach void setup() throws Exception { plugins = temporary.toRealPath().resolve("plugins"); target = plugins.resolve("ConnectionGuard"); Files.createDirectories(target); MigrationDatabases.setLoader(null); }
    @AfterEach void reset() { MigrationDatabases.setLoader(null); }
    void file(Path path, String text) throws IOException { Files.createDirectories(path.getParent()); Files.write(path, text.getBytes(StandardCharsets.UTF_8)); }
    Path source(String id, String config) throws IOException { Path directory = plugins.resolve(id);
        if (config.startsWith("synthetic:")) {
            String key = id.toLowerCase(Locale.ROOT);
            String known = key.equals("foxgate") ? "key: ''\n" : key.equals("proxyshield") ? "settings: {language: en}\n"
                    : key.equals("vpnguard") ? "join-enforcement: {enabled: false}\n" : key.equals("kaurivpn") ? "kickPlayers: true\n" : "Database: {Type: SQLITE}\n";
            config += known;
        }
        file(directory.resolve("config.yml"), config); return directory; }
    CompetitorMigration engine() throws IOException { return new CompetitorMigration(target); }
    Map<String, Object> config(CompetitorMigration.Plan plan) throws IOException { return MigrationFiles.yaml(plan.config.getBytes(StandardCharsets.UTF_8)); }
    List<AccessRule> rules(CompetitorMigration.Plan plan) { List<AccessRule> result = Arrays.asList(new Gson().fromJson(plan.rules, AccessRule[].class)); result.forEach(AccessRule::validate); return result; }
    String export(String allow, String deny) { return "schema: 1\ncomplete: true\nallow: " + allow + "\ndeny: " + deny + "\n"; }
    void assertBase(Map<String, Object> config) {
        assertEquals(true, MigrationFiles.get(config,"provider.vpn-failover.enabled"));
        assertEquals(true, MigrationFiles.get(config,"provider.local.connectionguard-intel.enabled"));
        assertEquals(5000, MigrationFiles.get(config,"lookup.deadline-ms"));
        assertEquals(1500, MigrationFiles.get(config,"lookup.http-timeout-ms"));
        assertEquals(8, MigrationFiles.get(config,"lookup.workers"));
        assertEquals(64, MigrationFiles.get(config,"lookup.queue-capacity"));
        assertEquals(128, MigrationFiles.get(config,"lookup.max-inflight"));
        assertEquals(30000, MigrationFiles.get(config,"lookup.circuit.pause-ms"));
        assertEquals(false, MigrationFiles.get(config,"identity.trust-forwarded-uuid"));
        assertEquals(false, MigrationFiles.get(config,"provider.vpn.ipcheck.enabled"));
        assertEquals("Disabled", MigrationFiles.get(config,"provider.geo.service"));
        assertEquals("SQLite", MigrationFiles.get(config,"provider.cache.type"));
    }
    @ParameterizedTest @ValueSource(strings={"foxgate","proxyshield","vpnguard","kaurivpn","advancedantivpn"})
    void discoversEveryBenchCompetitorAndRetainsCGAdvantages(String id) throws Exception {
        source(id, "synthetic: true\n"); assertEquals(Collections.singletonList(id), engine().discover());
        CompetitorMigration.Plan plan = engine().preview(id); assertTrue(plan.ready(), plan.blockers().toString()); assertBase(config(plan));
        assertEquals(0, rules(plan).size()); assertTrue(plan.report().toString().contains("not a claim"));
        assertFalse(Files.exists(target.resolve("config.yml")), "Preview must not create active config.");
    }
    @Test void casingAliasesWorkAndDuplicateDirectoryIsRejected() throws Exception {
        source("KauriVPN", "kickPlayers: true\n"); assertEquals(Collections.singletonList("kaurivpn"), engine().discover());
        source("kaurivpn", "kickPlayers: true\n");
        if (!Files.isSameFile(plugins.resolve("kaurivpn"),plugins.resolve("KauriVPN"))) assertThrows(IOException.class, () -> engine().discover());
        else assertEquals(Collections.singletonList("kaurivpn"),engine().discover());
    }
    @Test void foxWhitelistAndOfficialKeyImportWithoutLicenseOrNameBypass() throws Exception {
        Path src=source("FoxGate", "key: license-canary\nconfiguration: {scanner: {passive_mode: true}}\n");
        file(src.resolve("whitelist.yml"), "ips: ['192.0.2.17', '2001:db8::/48']\nnames: ['UntrustedName', '01234567-89ab-cdef-0123-456789abcdef']\n");
        file(src.resolve("services/proxycheck.yml"), "enabled: true\nkey: pc-canary\nurl: 'https://proxycheck.io/v2/{IP}?key={KEY}&vpn=1'\n");
        CompetitorMigration.Plan plan = engine().preview("foxgate"); assertTrue(plan.ready());
        assertEquals(3, rules(plan).size()); assertEquals("OBSERVE",MigrationFiles.get(config(plan),"operation.mode"));
        assertEquals("pc-canary",MigrationFiles.get(config(plan),"provider.vpn.proxycheck.api-key"));
        String report=plan.report().toString(); for(String hidden:Arrays.asList("pc-canary","license-canary","192.0.2.17","UntrustedName","01234567-89ab")) assertFalse(report.contains(hidden));
        assertFalse(plan.config.contains("license-canary"));
    }
    @Test void foxCustomEndpointNeverCopiesKeyAndDatabaseOrConditionalRulesBlock() throws Exception {
        Path src=source("foxgate", "synthetic: true\n");
        file(src.resolve("services/proxycheck.yml"),"enabled: true\nkey: private-key\nurl: 'https://proxycheck.io.attacker.invalid/{IP}'\n");
        file(src.resolve("database.yml"),"database: {whitelist_database: true}\n");
        file(src.resolve("modules/iprange.yml"),"enable: true\nblocker: {list: ['192.0.2.0/24']}\n");
        CompetitorMigration.Plan plan=engine().preview("foxgate"); assertFalse(plan.ready()); assertFalse(plan.config.contains("private-key")); assertEquals(2,plan.blockers().size());
    }
    @Test void proxyshieldMapsExplicitPolicyRulesAndKeyedProvidersNotWeights() throws Exception {
        source("ProxyShield", "settings: {fail-closed: true}\ndetection: {dry-run: true, asn: {enabled: true, blocked: [64500], allowed: ['AS64501']}}\n"
                + "whitelist: {ips: ['192.0.2.1', '2001:db8::/48'], players: ['UnsafeName']}\nblacklist: {ips: ['198.51.100.4-198.51.100.7']}\n"
                + "country: {enabled: true, mode: whitelist, codes: [de, pt], block-unknown: true}\napi: {mode: consensus, required-score: 9, timeout-seconds: 1, providers: {proxycheck: {enabled: true, key: pc-key}, iphub: {enabled: true, key: hub-key}}}\n");
        CompetitorMigration.Plan plan=engine().preview("proxyshield"); assertTrue(plan.ready()); Map<String,Object> cfg=config(plan);
        assertEquals("OBSERVE", MigrationFiles.get(cfg,"operation.mode")); assertEquals("CLOSED",MigrationFiles.get(cfg,"failure-policy.vpn"));
        assertEquals("CLOSED",MigrationFiles.get(cfg,"failure-policy.geo")); assertEquals(Arrays.asList("DE","PT"),MigrationFiles.get(cfg,"behavior.geo.list"));
        assertEquals("WHITELIST",MigrationFiles.get(cfg,"behavior.geo.type")); assertEquals("ProxyCheck",MigrationFiles.get(cfg,"provider.geo.service"));
        assertEquals(true, MigrationFiles.get(cfg,"provider.vpn.iphub.enabled"));
        assertEquals(Arrays.asList("proxycheck","iphub","blackbox","ipcheck","zowi","ipquery","ip-api"),MigrationFiles.get(cfg,"provider.vpn-failover.order"));
        assertEquals(1,MigrationFiles.get(cfg,"required-positive-flags")); assertEquals(5,rules(plan).size());
    }
    @Test void disabledAsnAndCountryRulesDoNotBecomeBlocks() throws Exception {
        source("proxyshield", "detection: {asn: {enabled: false, blocked: [64500]}}\ncountry: {enabled: false, codes: [DE]}\n");
        CompetitorMigration.Plan plan=engine().preview("proxyshield"); assertEquals(0,rules(plan).size());assertEquals("Disabled",MigrationFiles.get(config(plan),"provider.geo.service"));
    }
    @Test void missingKeyDoesNotEnableKeyRequiredProviderOrDiscardFreeChain() throws Exception {
        source("vpnguard", "join-enforcement: {enabled: true}\nproviders: {vpnapi: {enabled: true, key: ''}}\n");
        Map<String,Object> cfg=config(engine().preview("vpnguard")); assertEquals(false,MigrationFiles.get(cfg,"provider.vpn.vpnapi.enabled")); assertBase(cfg);
    }
    @Test void preservesExistingCustomizedCGConfigCloudOptOutAndKeys() throws Exception {
        file(target.resolve("config.yml"),"operation: {mode: OBSERVE}\ncloud: {enabled: false}\nprovider: {vpn-failover: {enabled: false, order: [ipquery]}, vpn: {proxycheck: {enabled: true, api-key: own-key}}}\nlookup: {http-timeout-ms: 2200}\n");
        source("proxyshield", "detection: {dry-run: false}\napi: {providers: {proxycheck: {enabled: true, key: foreign-key}, iphub: {enabled: true, key: hub-key}}}\n");
        CompetitorMigration.Plan plan=engine().preview("proxyshield"); Map<String,Object> cfg=config(plan);
        assertEquals("OBSERVE",MigrationFiles.get(cfg,"operation.mode")); assertEquals(false,MigrationFiles.get(cfg,"cloud.enabled"));
        assertEquals(false,MigrationFiles.get(cfg,"provider.vpn-failover.enabled")); assertEquals(Collections.singletonList("ipquery"),MigrationFiles.get(cfg,"provider.vpn-failover.order"));
        assertEquals("own-key",MigrationFiles.get(cfg,"provider.vpn.proxycheck.api-key")); assertEquals(2200,MigrationFiles.get(cfg,"lookup.http-timeout-ms"));
        assertFalse(plan.config.contains("foreign-key"));
    }
    @Test void noCountryRulesMeansNoAdditionalGeoRecipient() throws Exception {
        source("kaurivpn", "countries: {list: [], whitelist: true}\nlicense: paid-license\n");
        CompetitorMigration.Plan plan=engine().preview("kaurivpn");assertBase(config(plan));assertFalse(plan.config.contains("paid-license"));
    }
    @Test void kauriCountriesRetainIntentAndPrefixesNeverGrantBypass() throws Exception {
        source("kaurivpn", "kickPlayers: false\nprefixWhitelists: ['*']\ncountries: {list: [PT], whitelist: false}\ndatabase: {enabled: false}\n");
        CompetitorMigration.Plan plan=engine().preview("kaurivpn");assertEquals(false,MigrationFiles.get(config(plan),"behavior.vpn.kick-player"));
        assertEquals("BLACKLIST",MigrationFiles.get(config(plan),"behavior.geo.type"));assertEquals(0,rules(plan).size());assertTrue(plan.report().toString().contains("prefixWhitelists"));
    }
    @Test void sqliteVPNGuardImportsUUIDAndIPsButNotCache() throws Exception {
        Path src=source("vpnguard", "join-enforcement: {enabled: true}\n");
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+src.resolve("database.db")); Statement sql=db.createStatement()) {
            sql.execute("CREATE TABLE vpn_whitelist_names(uuid TEXT,name TEXT)"); sql.execute("INSERT INTO vpn_whitelist_names VALUES('01234567-89ab-cdef-0123-456789abcdef','LegacyName')");
            sql.execute("CREATE TABLE vpn_whitelist_ips(ip TEXT)"); sql.execute("INSERT INTO vpn_whitelist_ips VALUES('192.0.2.1')");
            sql.execute("CREATE TABLE vpn_cache(ip TEXT,is_vpn INTEGER)"); sql.execute("INSERT INTO vpn_cache VALUES('198.51.100.99',0)");
        }
        byte[] original=Files.readAllBytes(src.resolve("database.db")); CompetitorMigration.Plan plan=engine().preview("vpnguard"); assertTrue(plan.ready(),plan.blockers().toString());
        assertEquals(2,rules(plan).size()); assertFalse(plan.rules.contains("198.51.100.99"));assertArrayEquals(original,Files.readAllBytes(src.resolve("database.db")));
        AccessRule uuid=rules(plan).stream().filter(r->r.getType()==AccessRule.Target.UUID).findFirst().get();
        assertFalse(uuid.matches("192.0.2.2",UUID.fromString(uuid.getTarget()),false,AccessRule.Scope.VPN,1));
        assertTrue(uuid.matches("192.0.2.2",UUID.fromString(uuid.getTarget()),true,AccessRule.Scope.VPN,1));
    }
    @Test void advancedSQLiteImportsSixAdminTablesNeverCachedFlagsAndRetainsDefaultChain() throws Exception {
        Path src=source("advancedantivpn", "Services: {ProxyCheck: {Enabled: true, Key: pc-secret}, VPNAPI: {Enabled: true, Key: vpn-secret}}\nGeoLite2: {Enabled: true, Mode: BLACKLIST, Countries: [de], MaxMind Key: maxmind-secret}\nFlagged Threshold: 2\n");
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+src.resolve("database.db")); Statement sql=db.createStatement()) {
            for(String[] table:new String[][]{{"whitelist","ip","192.0.2.10"},{"blacklist","ip","198.51.100.10"},{"uuid_whitelist","uuid","01234567-89ab-cdef-0123-456789abcdef"},{"uuid_blacklist","uuid","11234567-89ab-cdef-0123-456789abcdef"},{"wildcard_whitelist","pattern","203.0.113.*"},{"wildcard_blacklist","pattern","198.18.*.*"}}) {
                sql.execute("CREATE TABLE aavpn_"+table[0]+"("+table[1]+" TEXT)"); sql.execute("INSERT INTO aavpn_"+table[0]+" VALUES('"+table[2]+"')"); }
            sql.execute("CREATE TABLE aavpn_ip_information(ip TEXT,is_flagged INTEGER)"); sql.execute("INSERT INTO aavpn_ip_information VALUES('198.51.100.99',1)");
        }
        CompetitorMigration.Plan plan=engine().preview("advancedantivpn");assertTrue(plan.ready(),plan.blockers().toString());assertEquals(6,rules(plan).size());
        assertFalse(plan.rules.contains("198.51.100.99")); assertFalse(plan.config.contains("maxmind-secret"));
        assertEquals(true,MigrationFiles.get(config(plan),"provider.vpn.vpnapi.enabled")); assertEquals(1,MigrationFiles.get(config(plan),"required-positive-flags"));
        assertEquals(Collections.singletonList("DE"),MigrationFiles.get(config(plan),"behavior.geo.list"));
    }
    @ParameterizedTest @ValueSource(strings={"whitelisted-ips","whitelisted-ranges"})
    void kauriH2ImportsLiteralAndCIDRAdminListsReadOnly(String rangeTable) throws Exception {
        Path src=source("kaurivpn", "database: {enabled: true, type: H2, username: root, password: test-only}\n"); Files.createDirectories(src.resolve("databases"));
        String column=rangeTable.endsWith("ranges")?"cidr_string":"ip";
        try(Connection db=DriverManager.getConnection("jdbc:h2:file:"+src.resolve("databases/database"),"root","test-only"); Statement sql=db.createStatement()) {
            sql.execute("CREATE TABLE \"whitelisted\"(\"uuid\" VARCHAR(36))");sql.execute("INSERT INTO \"whitelisted\" VALUES('01234567-89ab-cdef-0123-456789abcdef')");
            sql.execute("CREATE TABLE \""+rangeTable+"\"(\""+column+"\" VARCHAR(50))"); sql.execute("INSERT INTO \""+rangeTable+"\" VALUES('2001:db8::/48')");
            sql.execute("CREATE TABLE responses(ip VARCHAR(50))"); sql.execute("INSERT INTO responses VALUES('198.51.100.99')");
        }
        byte[] before=Files.readAllBytes(src.resolve("databases/database.mv.db")); CompetitorMigration.Plan plan=engine().preview("kaurivpn");
        assertTrue(plan.ready(),plan.blockers().toString()); assertEquals(2,rules(plan).size());assertFalse(plan.rules.contains("198.51.100.99"));assertFalse(plan.config.contains("test-only"));
        assertArrayEquals(before,Files.readAllBytes(src.resolve("databases/database.mv.db"))); assertFalse(Files.exists(src.resolve("databases/database.lock.db")));
    }
    @Test void sqliteWalAndUnknownSchemasBlockInsteadOfLosingAdminRules() throws Exception {
        Path src=source("advancedantivpn", "Database: {Type: SQLITE}\n");file(src.resolve("database.db-wal"),"synthetic-wal");
        assertFalse(engine().preview("advancedantivpn").ready());Files.delete(src.resolve("database.db-wal"));
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+src.resolve("database.db"));Statement sql=db.createStatement()){sql.execute("CREATE TABLE unknown(data TEXT)");}
        assertFalse(engine().preview("advancedantivpn").ready());
    }
    @ParameterizedTest @ValueSource(strings={"kaurivpn","advancedantivpn"})
    void remoteDatabasesAreNeverContactedAndExplicitCompleteExportUnblocks(String id) throws Exception {
        Path src=source(id,id.equals("kaurivpn")?"database: {enabled: true, type: MySQL, ip: never-contact.invalid}\n":"Database: {Type: MYSQL, IP: never-contact.invalid}\n");
        assertFalse(engine().preview(id).ready());file(src.resolve("migration-rules.yml"),export("['192.0.2.0/24']","['198.51.100.1']"));
        CompetitorMigration.Plan plan=engine().preview(id);assertTrue(plan.ready(),plan.blockers().toString());assertEquals(2,rules(plan).size());assertFalse(plan.config.contains("never-contact.invalid"));
    }
    @Test void explicitExportRequiresCompleteContract() throws Exception {
        Path src=source("foxgate","synthetic: true\n");file(src.resolve("migration-rules.yml"),"allow: []\ndeny: []\n");assertThrows(IOException.class,()->engine().preview("foxgate"));
    }
    @Test void IPRangeDecomposesWithoutBroadeningIncludingIPv6() throws Exception {
        source("proxyshield","blacklist: {ips: ['192.0.2.1-192.0.2.6', '2001:db8::1-2001:db8::3']}\n");
        List<AccessRule> rules=rules(engine().preview("proxyshield")); assertEquals(6,rules.size());
        for(int n=0;n<=7;n++){final String ip="192.0.2."+n;assertEquals(n>=1&&n<=6,rules.stream().anyMatch(r->r.matches(ip,null,false,AccessRule.Scope.VPN,1)));}
        for(int n=0;n<=4;n++){final String ip="2001:db8::"+Integer.toHexString(n);assertEquals(n>=1&&n<=3,rules.stream().anyMatch(r->r.matches(ip,null,false,AccessRule.Scope.GEO,1)));}
    }
    @Test void ambiguousDenyWildcardBlocksAndInvalidAllowNameIsVisible() throws Exception {
        source("proxyshield","blacklist: {ips: ['192.*.2.*']}\nwhitelist: {players: ['UnknownName']}\n");
        CompetitorMigration.Plan plan=engine().preview("proxyshield");assertFalse(plan.ready());assertTrue(plan.report().toString().contains("Unresolved ALLOW"));assertFalse(plan.report().toString().contains("UnknownName"));
    }
    @ParameterizedTest @ValueSource(strings={"x: 1\nx: 2\n","x: !!java.lang.String ['secret-canary']\n","x: &a [1]\ny: *a\n","x: 2026-10-07\n"})
    void unsafeOrAmbiguousYamlIsRejectedWithoutLeakingValues(String input) throws Exception {
        source("foxgate",input);IOException error=assertThrows(IOException.class,()->engine().preview("foxgate"));assertFalse(error.toString().contains("secret-canary"));
    }
    @Test void invalidCompleteDraftRejectsInsteadOfPartialImport() throws Exception {
        file(target.resolve("config.yml"),"lookup: {workers: -1}\n");source("foxgate","synthetic: true\n"); assertThrows(IOException.class,()->engine().preview("foxgate"));
    }
    @Test void currentCloudOverlayAndPolicyJournalRequireExplicitRelease() throws Exception {
        source("foxgate","synthetic: true\n");file(target.resolve("cloud/managed-config.json"),"{\"version\":1,\"values\":{\"operation.mode\":\"OBSERVE\"}}\n");
        CompetitorMigration.Plan cloud=engine().preview("foxgate");assertFalse(cloud.ready());assertThrows(IOException.class,()->engine().stage(cloud));
        Files.delete(target.resolve("cloud/managed-config.json"));file(target.resolve("access-rules.json"),"{\"schema\":1}\n");assertFalse(engine().preview("foxgate").ready());
    }
    @Test void symlinkSourceConfigOrTargetParentIsRejected() throws Exception {
        Path src=source("foxgate","synthetic: true\n");Files.delete(src.resolve("config.yml"));file(plugins.resolve("outside.yml"),"x: secret\n");Files.createSymbolicLink(src.resolve("config.yml"),plugins.resolve("outside.yml"));
        assertThrows(IOException.class,()->engine().preview("foxgate"));
        Files.createSymbolicLink(plugins.resolve("linked"),target);assertThrows(IOException.class,()->new CompetitorMigration(plugins.resolve("linked")));
    }
    @ParameterizedTest @ValueSource(strings={"source","target","rules","new-source-file","cloud"})
    void stalenessSincePreviewIsRejected(String change) throws Exception {
        Path src=source("foxgate","synthetic: true\n");CompetitorMigration.Plan plan=engine().preview("foxgate");
        switch(change){case "source":file(src.resolve("config.yml"),"synthetic: false\n");break;case "target":file(target.resolve("config.yml"),"x: 1\n");break;case "rules":file(target.resolve("access-rules.json"),"[]\n");break;case "new-source-file":file(src.resolve("whitelist.yml"),"ips: ['192.0.2.1']\n");break;case "cloud":file(target.resolve("cloud/managed-config.json"),"{}\n");break;}
        assertThrows(IOException.class,()->engine().stage(plan));assertFalse(Files.exists(target.resolve("migration/pending.json")));
    }
    @Test void stageDoesNotApplyAndRestartCommitsOnceWithExactPrivateBackups() throws Exception {
        String original="operation: {mode: OBSERVE}\n";file(target.resolve("config.yml"),original);file(target.resolve("access-rules.json"),"[]\n");
        Path src=source("foxgate","synthetic: true\n");file(src.resolve("whitelist.yml"),"ips: ['192.0.2.1']\n");byte[] source=Files.readAllBytes(src.resolve("whitelist.yml"));
        CompetitorMigration.Plan plan=engine().preview("foxgate");engine().stage(plan);assertEquals(original,new String(Files.readAllBytes(target.resolve("config.yml")),StandardCharsets.UTF_8));
        List<String> log=new ArrayList<>();engine().beforeStart(log::add);assertEquals(plan.config,new String(Files.readAllBytes(target.resolve("config.yml")),StandardCharsets.UTF_8));
        assertEquals(original,new String(Files.readAllBytes(target.resolve("migration/backups/"+plan.id()+"/config.yml")),StandardCharsets.UTF_8));
        assertArrayEquals(source,Files.readAllBytes(src.resolve("whitelist.yml")));assertEquals(1,new AccessRuleStore(target).snapshot().size());
        assertFalse(Files.exists(target.resolve("migration/pending.json")));assertTrue(Files.exists(target.resolve("migration/completed.json")));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(target.resolve("config.yml")));
        engine().beforeStart(log::add);assertEquals(1,log.size());
    }
    @Test void existingRuleIsPreservedAndDuplicateAdminEntriesAreIdempotent() throws Exception {
        new AccessRuleStore(target).add(AccessRule.Effect.ALLOW,AccessRule.Scope.ALL,"192.0.2.1",0,"Existing manual rule");
        Path src=source("foxgate","synthetic: true\n");file(src.resolve("whitelist.yml"),"ips: ['192.0.2.1', '192.0.2.1', '198.51.100.1']\n");
        CompetitorMigration.Plan plan=engine().preview("foxgate");assertEquals(2,rules(plan).size());engine().stage(plan);engine().beforeStart(x->{});
        assertEquals(plan.rules,engine().preview("foxgate").rules);
    }
    @Test void planTamperingRejectedAndBootstrapKeepsWorkingProtection() throws Exception {
        source("foxgate","synthetic: true\n");file(target.resolve("config.yml"),"operation: {mode: OBSERVE}\n"); CompetitorMigration.Plan plan=engine().preview("foxgate");engine().stage(plan);
        Path pending=target.resolve("migration/pending.json");String json=new String(Files.readAllBytes(pending),StandardCharsets.UTF_8);file(pending,json.replace("source=foxgate","source=proxyshield"));
        // Mutate a guaranteed report field without changing its preview token.
        JsonObject value=JsonParser.parseString(new String(Files.readAllBytes(pending),StandardCharsets.UTF_8)).getAsJsonObject();value.getAsJsonArray("report").add("tampered");file(pending,new Gson().toJson(value));
        assertThrows(IOException.class,()->engine().beforeStart(x->{}));List<String> logs=new ArrayList<>();MigrationBootstrap.beforeStart(target,logs::add);
        assertTrue(logs.get(0).contains("existing CG files retained"));assertEquals("operation: {mode: OBSERVE}\n",new String(Files.readAllBytes(target.resolve("config.yml")),StandardCharsets.UTF_8));
    }
    @Test void interruptedCommitRollsForwardWithoutRereadingSourceOrLosingRules() throws Exception {
        Path src=source("foxgate","synthetic: true\n");file(src.resolve("whitelist.yml"),"ips: ['192.0.2.1']\n");CompetitorMigration.Plan plan=engine().preview("foxgate");engine().stage(plan);
        Files.move(target.resolve("migration/pending.json"),target.resolve("migration/commit.json"));file(target.resolve("config.yml"),plan.config);Files.delete(src.resolve("config.yml"));
        engine().beforeStart(x->{});assertEquals(1,new AccessRuleStore(target).snapshot().size());assertFalse(Files.exists(target.resolve("migration/commit.json")));
    }
    @Test void interruptedCommitWithExternalRuleEditsStopsRatherThanOverwrites() throws Exception {
        source("foxgate","synthetic: true\n");CompetitorMigration.Plan plan=engine().preview("foxgate");engine().stage(plan);
        Files.move(target.resolve("migration/pending.json"),target.resolve("migration/commit.json"));file(target.resolve("config.yml"),plan.config);file(target.resolve("access-rules.json"),"[]\n");
        assertThrows(IllegalStateException.class,()->MigrationBootstrap.beforeStart(target,x->{}));assertEquals("[]\n",new String(Files.readAllBytes(target.resolve("access-rules.json")),StandardCharsets.UTF_8));
    }
    @Test void competitorStillInstalledPreventsApplyAndCancelIsSafe() throws Exception {
        source("foxgate","synthetic: true\n");CompetitorMigration.Plan plan=engine().preview("foxgate");engine().stage(plan);
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(plugins.resolve("old-plugin.jar")))){zip.putNextEntry(new ZipEntry("velocity-plugin.json"));zip.write("{\"id\":\"foxgate\"}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        List<String> logs=new ArrayList<>();engine().beforeStart(logs::add);assertFalse(Files.exists(target.resolve("config.yml")));assertTrue(logs.get(0).contains("Remove competitor JARs"));
        engine().cancel();assertFalse(Files.exists(target.resolve("migration/pending.json")));assertTrue(Files.exists(plugins.resolve("old-plugin.jar")));
    }
    @Test void autoDiscoveryNoticeIsOnceAndNeverTransfersFilesOrKeys() throws Exception {
        source("KauriVPN","license: secret\n");List<String> logs=new ArrayList<>();engine().beforeStart(logs::add);engine().beforeStart(logs::add);
        assertEquals(1,logs.size());assertFalse(logs.toString().contains("secret"));assertFalse(Files.exists(target.resolve("config.yml")));
    }
    @Test void activeEmptyCountryWhitelistIsNotSilentlyDisabled() throws Exception {
        source("proxyshield", "country: {enabled: true, mode: whitelist, codes: [], block-unknown: true}\n");
        CompetitorMigration.Plan plan=engine().preview("proxyshield");
        assertEquals("ProxyCheck",MigrationFiles.get(config(plan),"provider.geo.service"));
        assertEquals("WHITELIST",MigrationFiles.get(config(plan),"behavior.geo.type"));
        assertEquals("CLOSED",MigrationFiles.get(config(plan),"failure-policy.geo"));
        assertTrue(plan.report().toString().contains("every KNOWN country"));
    }
    @Test void customCommandsAsOnlyEnforcementAreNotSilentlyDiscarded() throws Exception {
        source("kaurivpn", "kickPlayers: false\ncommands: {enabled: true}\n");
        assertFalse(engine().preview("kaurivpn").ready());
        source("advancedantivpn", "Actions: {Block: {Enabled: false}, Commands: {Enabled: true}}\n");
        assertFalse(engine().preview("advancedantivpn").ready());
    }

    @Test void unknownConfigSchemasAndFutureVersionsRequireExplicitReview() throws Exception {
        source("proxyshield", "new-unknown-schema: true\n");assertThrows(IOException.class,()->engine().preview("proxyshield"));
        source("vpnguard", "config-version: 99\n");assertThrows(IOException.class,()->engine().preview("vpnguard"));
    }

}
