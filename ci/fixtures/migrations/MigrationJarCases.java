import com.github.gerolndnr.connectionguard.core.migration.CompetitorMigration;
import com.github.gerolndnr.connectionguard.core.rules.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Execute the shipped parser/transaction/classes, not a reimplementation in the harness. */
public final class MigrationJarCases {
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    private static void write(Path path, String text) throws Exception { Files.createDirectories(path.getParent()); Files.write(path,text.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]).toRealPath(); int passed=0;
        for(String source:CompetitorMigration.SOURCES) {
            Path plugins=root.resolve(source).resolve("plugins"), target=plugins.resolve("ConnectionGuard"), old=plugins.resolve(source);
            Files.createDirectories(target); String minimal=source.equals("foxgate")?"key: ''\n":source.equals("proxyshield")?"settings: {language: en}\n":source.equals("vpnguard")?"join-enforcement: {enabled: false}\n":source.equals("kaurivpn")?"kickPlayers: true\n":"Database: {Type: SQLITE}\n";
            write(old.resolve("config.yml"),minimal);
            write(old.resolve("migration-rules.yml"),"schema: 1\ncomplete: true\nallow: ['192.0.2.1', '01234567-89ab-cdef-0123-456789abcdef']\ndeny: ['198.51.100.0/24']\n");
            CompetitorMigration engine=new CompetitorMigration(target);require(engine.discover().equals(Collections.singletonList(source)),source+":discover");passed++;
            CompetitorMigration.Plan plan=engine.preview(source);require(plan.ready(),source+":preview");
            require(!plan.report().toString().contains("192.0.2.1"),source+":redaction");require(!Files.exists(target.resolve("config.yml")),source+":no-preview-write");passed++;
            engine.stage(plan);require(Files.exists(target.resolve("migration/pending.json")),source+":stage");require(!Files.exists(target.resolve("config.yml")),source+":no-live-write");passed++;
            List<String> logs=new ArrayList<>();engine.beforeStart(logs::add);require(logs.size()==1,source+":commit");
            AccessRuleStore store=new AccessRuleStore(target);require(store.snapshot().size()==3,source+":rule-count");
            require(store.snapshot().stream().anyMatch(r->r.getEffect()==AccessRule.Effect.DENY&&r.matches("198.51.100.17",null,false,AccessRule.Scope.VPN,1)),source+":deny");
            UUID uuid=UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
            require(store.snapshot().stream().noneMatch(r->r.getType()==AccessRule.Target.UUID&&r.matches("203.0.113.1",uuid,false,AccessRule.Scope.VPN,1)),source+":untrusted-identity");passed++;
            byte[] before=Files.readAllBytes(target.resolve("config.yml"));engine.beforeStart(logs::add);
            require(Arrays.equals(before,Files.readAllBytes(target.resolve("config.yml")))&&logs.size()==1,source+":idempotence");passed++;
        }
        System.out.println("migration-jar-cases="+passed+"; sources=5; no-network=true; real-player-login=false; competitive-accuracy=false");
    }
}
