package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.*;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class LocalLookupTest {
    @TempDir Path directory;
    @BeforeEach void setup() {
        ConnectionGuard.configureLookup(LookupSettings.defaults()); ConnectionGuard.setCacheProvider(new NoCacheProvider());
        ConnectionGuard.setRequiredPositiveFlags(1); ConnectionGuard.setVpnProviders(new ArrayList<>());
    }
    @AfterEach void reset() { ConnectionGuard.setCacheProvider(new NoCacheProvider()); ConnectionGuard.setVpnProviders(new ArrayList<>()); }
    private LocalSnapshot list(LocalSource.Kind kind, String value) {
        long now = System.currentTimeMillis();
        return LocalSnapshot.parse(LocalDataStoreTest.source("fixture", kind), value.getBytes(java.nio.charset.StandardCharsets.UTF_8), now - 100, now, "IMPORT_AS_OF", now);
    }
    @Test void unknownGenericVoteRetainsHostingEvidenceAndCanEnforceATypeRule() throws Exception {
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new LocalVpnProvider(list(LocalSource.Kind.HOSTING, "192.0.2.0/24\n")))));
        VpnResult result = ConnectionGuard.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS);
        assertEquals(ProviderVote.Status.UNKNOWN, result.getStatus());
        ProviderVote vote = result.getVotes().get(0);
        assertEquals(FailureReason.NO_EVIDENCE, vote.getReason()); assertEquals(Boolean.TRUE, vote.getDetails().get(DetectionDetails.Type.HOSTING));
        AccessRule deny = new AccessRule("hosting", AccessRule.Effect.DENY, AccessRule.Scope.VPN, "type:HOSTING", 0, "Synthetic hosting test");
        assertTrue(EvidencePolicy.evaluate(Collections.singletonList(deny), "192.0.2.1", null, false, AccessRule.Scope.VPN, result.getVotes(), System.currentTimeMillis()).isDenied());
        ProviderVote expired = new ProviderVote(vote.getProvider(), vote.getStatus(), vote.getReason(), 1, vote.getDetails(), 1, vote.getSourceVersion());
        assertFalse(EvidencePolicy.evaluate(Collections.singletonList(deny), "192.0.2.1", null, false, AccessRule.Scope.VPN, Collections.singletonList(expired), 2).isDenied());
    }
    @Test void enrichmentDoesNotChangeGenericThresholdOrCreateACleanLocalOnlyVote() throws Exception {
        LocalVpnProvider hosting = new LocalVpnProvider(list(LocalSource.Kind.HOSTING, "198.51.100.0/24\n"));
        VpnProvider negative = ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false)));
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(negative, hosting)));
        assertEquals(ProviderVote.Status.NEGATIVE, ConnectionGuard.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS).getStatus());
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(new LocalVpnProvider(list(LocalSource.Kind.VPN, "198.51.100.0/24\n")), hosting)));
        assertEquals(ProviderVote.Status.UNKNOWN, ConnectionGuard.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS).getStatus());
    }
    @Test void expiredEvidenceInvalidatesCacheBeforeOrdinaryTtl() throws Exception {
        long now = System.currentTimeMillis(); VpnResult result = new VpnResult("192.0.2.1", true); result.setCachedOn(now);
        result.setVotes(Collections.singletonList(new ProviderVote("local.fixture", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1,
                list(LocalSource.Kind.VPN, "192.0.2.0/24\n").vpn("192.0.2.1", now).getDetails(), now - 1, null)));
        assertFalse(CacheCodec.vpn(CacheCodec.encode(result), "192.0.2.1", 86400000).isPresent());
        ConnectionGuard.setCacheProvider(new NoCacheProvider() {
            @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip) { return CompletableFuture.completedFuture(Optional.of(result)); }
        });
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new LocalVpnProvider(list(LocalSource.Kind.VPN, "198.51.100.0/24\n")))));
        assertEquals(ProviderVote.Status.UNKNOWN, ConnectionGuard.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS).getStatus());
    }
    @Test void aLocalPositiveThatExpiresWhileAnotherProviderIsPendingCannotDecideTheAggregate() throws Exception {
        CompletableFuture<Optional<VpnResult>> pending = new CompletableFuture<>(); CountDownLatch called = new CountDownLatch(2);
        long until = System.currentTimeMillis() + 1000;
        VpnResult local = new VpnResult("192.0.2.1", true); local.setValidUntil(until);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(ip -> { called.countDown(); return CompletableFuture.completedFuture(Optional.of(local)); },
                ip -> { called.countDown(); return pending; })));
        CompletableFuture<VpnResult> query = ConnectionGuard.getVpnResult("192.0.2.1");
        try {
            assertTrue(called.await(500, TimeUnit.MILLISECONDS));
            Thread.sleep(Math.max(1, until - System.currentTimeMillis() + 5));
            pending.complete(Optional.of(new VpnResult("192.0.2.1", false)));
            VpnResult answer = query.get(2, TimeUnit.SECONDS);
            assertEquals(ProviderVote.Status.UNKNOWN, answer.getStatus()); assertEquals(FailureReason.STALE_DATA, answer.getVotes().get(0).getReason());
        } finally { pending.complete(Optional.of(new VpnResult("192.0.2.1", false))); query.get(2, TimeUnit.SECONDS); }
    }
    @Test void contentChangesActivateANewNamespaceAndCannotReuseOldPositiveSqliteEvidence() throws Exception {
        Map<String, Object> fields = new HashMap<>(); fields.put("provider.vpn.local.enabled", true); fields.put("provider.geo.service", "Disabled");
        Map<String, Object> source = new HashMap<>(); source.put("id", "vpn"); source.put("type", "VPN"); source.put("source", "Synthetic fixture");
        source.put("license", "MIT"); source.put("notice", "Connection Guard contributors"); fields.put("provider.local.sources", Collections.singletonList(source));
        Path root = directory.toRealPath(); ProviderConfiguration empty = new ProviderConfiguration(fields::get, Collections.singletonList("local"), root);
        SQLiteCacheProvider cache = new SQLiteCacheProvider(root.resolve("cache.db").toString());
        ConnectionGuard.setCacheProvider(cache); assertTrue(cache.setup().get(2, TimeUnit.SECONDS));
        try {
            empty.localStore.prepareInbox(); Path inbox = root.resolve("local-data/inbox/list.txt"); long now = System.currentTimeMillis();
            Files.write(inbox, "192.0.2.0/24\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); empty.localStore.importFile("vpn", "list.txt", now - 1000, now);
            ProviderConfiguration first = empty.refreshLocal(); assertNotEquals(empty.cacheNamespace, first.cacheNamespace); ConnectionGuard.applyProviders(first);
            assertTrue(ConnectionGuard.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS).isVpn());
            // Await the serial writer via a cache read, then prove the old namespace has an entry.
            assertTrue(cache.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS).isPresent());
            Files.write(inbox, "198.51.100.0/24\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); first.localStore.importFile("vpn", "list.txt", now, now);
            ProviderConfiguration next = first.refreshLocal(); assertNotEquals(first.cacheNamespace, next.cacheNamespace); ConnectionGuard.applyProviders(next);
            VpnResult absent = ConnectionGuard.getVpnResult("192.0.2.1").get(2, TimeUnit.SECONDS);
            assertEquals(ProviderVote.Status.UNKNOWN, absent.getStatus()); assertFalse(absent.isFromCache());
            assertEquals(first.localSnapshots.get(0).version, first.localSnapshots.get(0).vpn("192.0.2.1", now).getSourceVersion());
        } finally { cache.disband().get(2, TimeUnit.SECONDS); }
    }
}
