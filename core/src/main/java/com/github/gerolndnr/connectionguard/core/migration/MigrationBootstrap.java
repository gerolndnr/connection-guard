package com.github.gerolndnr.connectionguard.core.migration;

import java.io.IOException;
import java.nio.file.*;
import java.util.function.Consumer;

/** Discovery problems never disable working protection; a half-commit must recover consistently. */
public final class MigrationBootstrap {
    private MigrationBootstrap() { }
    public static void beforeStart(Path directory, Consumer<String> notice) {
        try { new CompetitorMigration(directory).beforeStart(notice); }
        catch (IOException | RuntimeException invalid) {
            if (Files.exists(directory.resolve("migration/commit.json"), LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Interrupted competitor migration needs recovery; inspect migration/backups before starting (values redacted).");
            notice.accept("Competitor migration unavailable; existing CG files retained. Review /cg migrate and docs/MIGRATIONS.md (values redacted).");
        }
    }
}
