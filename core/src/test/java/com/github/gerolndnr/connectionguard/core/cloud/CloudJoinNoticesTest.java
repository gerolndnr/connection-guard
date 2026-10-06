package com.github.gerolndnr.connectionguard.core.cloud;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CloudJoinNoticesTest {
    @TempDir Path directory;
    private Path file() { return directory.resolve("staff-dashboard-notices-v1.json"); }
    private void write(String json) throws IOException { Files.write(file(), json.getBytes(StandardCharsets.UTF_8)); }

    @Test void concurrentJoinsReserveOnceAndPersistPrivately() throws Exception {
        CloudJoinNotices notices = new CloudJoinNotices(directory);
        UUID staff = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) results.add(pool.submit(() -> notices.reserve(staff)));
            int accepted = 0; for (Future<Boolean> result : results) if (result.get()) accepted++;
            assertEquals(1, accepted);
        } finally { pool.shutdownNow(); }
        notices.save();
        JsonObject stored = JsonParser.parseString(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, stored.getAsJsonArray("seen").size());
        assertTrue(stored.getAsJsonArray("seen").get(0).getAsString().matches("[0-9a-f]{64}"));
        assertFalse(stored.toString().contains(staff.toString()));
        if (Files.getFileStore(file()).supportsFileAttributeView("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file()));
        CloudJoinNotices restarted = new CloudJoinNotices(directory); restarted.load();
        assertFalse(restarted.reserve(staff));
        assertTrue(restarted.reserve(UUID.randomUUID()));
        assertFalse(restarted.reserve(null));
    }

    @Test void rejectsInvalidVersionAndPlaintextIdentity() throws Exception {
        for (String json : Arrays.asList("{\"version\":2,\"seen\":[]}", "{\"version\":1,\"seen\":[\"" + UUID.randomUUID() + "\"]}", "{\"version\":1}", "[]")) {
            write(json);
            assertThrows(IOException.class, () -> new CloudJoinNotices(directory).load());
        }
    }

    @Test void rejectsDuplicateEntriesAndOverlargeFiles() throws Exception {
        String hash = String.join("", Collections.nCopies(64, "a"));
        write("{\"version\":1,\"seen\":[\"" + hash + "\",\"" + hash + "\"]}");
        assertThrows(IOException.class, () -> new CloudJoinNotices(directory).load());
        Files.write(file(), new byte[300001]);
        assertThrows(IOException.class, () -> new CloudJoinNotices(directory).load());
    }

    @Test void refusesSymlinkOnReadAndWrite() throws Exception {
        Path target = directory.resolve("outside"); write("{}"); Files.move(file(), target);
        Files.createSymbolicLink(file(), target);
        CloudJoinNotices notices = new CloudJoinNotices(directory);
        assertThrows(IOException.class, notices::load);
        assertTrue(notices.reserve(UUID.randomUUID()));
        assertThrows(IOException.class, notices::save);
        assertEquals("{}", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test void boundsInstallationMemoryAndFileWithoutEvictingPreviousStaff() throws Exception {
        CloudJoinNotices notices = new CloudJoinNotices(directory);
        UUID first = new UUID(0, 1);
        for (int i = 1; i <= 4096; i++) assertTrue(notices.reserve(new UUID(0, i)));
        assertFalse(notices.reserve(new UUID(0, 4097)));
        notices.save();
        assertTrue(Files.size(file()) < 300000);
        CloudJoinNotices restarted = new CloudJoinNotices(directory); restarted.load();
        assertFalse(restarted.reserve(first));
        assertFalse(restarted.reserve(new UUID(0, 4097)));
    }

    @Test void presentationSourcePreservesQueryAndFragmentAndRejectsArbitraryValues() {
        assertEquals("https://example.org/link?src=join", CloudLinkNotice.sourced("https://example.org/link", "join"));
        assertEquals("https://example.org/link?x=1&src=command#help", CloudLinkNotice.sourced("https://example.org/link?x=1#help", "command"));
        assertThrows(IllegalArgumentException.class, () -> CloudLinkNotice.sourced("https://example.org", "other&inject=1"));
    }
}
