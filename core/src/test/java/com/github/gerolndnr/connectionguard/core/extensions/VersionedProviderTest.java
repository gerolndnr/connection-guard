package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.*;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class VersionedProviderTest {
    @TempDir Path directory;
    private CacheProvider cache;
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));
    @BeforeEach void initialize() throws Exception {
        awaitQuiescence();
        ExtensionRegistry.closeAll(); ConnectionGuard.setLogger(null);
        ConnectionGuard.applySettings(GuardSettings.defaults());
        cache = new NoCacheProvider(); ConnectionGuard.setCacheProvider(cache);
        ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.setRequiredPositiveFlags(1);
    }
    @AfterEach void cleanup() throws Exception {
        ExtensionRegistry.closeAll();
        awaitQuiescence();
        if (cache != null) cache.disband().get(2, TimeUnit.SECONDS);
        ConnectionGuard.setCacheProvider(new NoCacheProvider()); ConnectionGuard.setVpnProviders(new ArrayList<>());
    }
    private static void awaitQuiescence() throws InterruptedException {
        // A completed result can still be unwinding callbacks on an owned worker/timer.
        // Do not mutate shared fixture state until the physical work has left the pool.
        LookupRuntime runtime = ConnectionGuard.getLookupRuntime();
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!runtime.isIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(runtime.isIdle(), "Previous fixture left lookup work or deadlines active.");
    }
    private Map<String, Object> values(String id, boolean voting) {
        Map<String, Object> config = new HashMap<>(), source = new HashMap<>();
        source.put("id", id); source.put("voting", voting);
        config.put("integrations.providers.enabled", true); config.put("integrations.providers.sources", Collections.singletonList(source));
        config.put("provider.geo.service", "Disabled"); return config;
    }
    private ProviderConfiguration draft(String id, boolean voting) { return new ProviderConfiguration(values(id, voting)::get, Collections.emptyList()); }
    private void select(ProviderConfiguration draft) {
        ConnectionGuard.setVpnProviders(draft.providers); ConnectionGuard.setRequiredPositiveFlags(draft.threshold); cache.setNamespace(draft.cacheNamespace);
    }
    private VpnResult query(String ip) throws Exception { return ConnectionGuard.getVpnResult(ip).get(2, TimeUnit.SECONDS); }
    @Test void metadataAndObservationAreImmutableAndStrictlyTyped() {
        Map<DetectionMetadata.Type, Boolean> types = new EnumMap<>(DetectionMetadata.Type.class); types.put(DetectionMetadata.Type.HOSTING, true);
        DetectionMetadata metadata = new DetectionMetadata(types, 15169L, "Synthetic ISP", "Synthetic operator", "JP", 80, null);
        types.clear(); assertEquals(Boolean.TRUE, metadata.getTypes().get(DetectionMetadata.Type.HOSTING));
        assertThrows(UnsupportedOperationException.class, () -> metadata.getTypes().clear());
        assertThrows(IllegalArgumentException.class, () -> new DetectionMetadata(null, 0L, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DetectionMetadata(null, null, null, null, null, 101, null));
        assertThrows(IllegalArgumentException.class, () -> new DetectionObservation(DetectionObservation.Status.NEGATIVE, DetectionObservation.Reason.NETWORK, metadata, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDescriptor("endpoint://secret", "1", HASH, true));
    }
    @Test void registrationDoesNotActivateInstalledCodeAndMissingSelectionIsUnknown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "1", HASH, true), ip -> {
            calls.incrementAndGet(); return CompletableFuture.completedFuture(DetectionObservation.negative(DetectionMetadata.empty()));
        });
        assertEquals(ProviderVote.Status.UNKNOWN, query("192.0.2.1").getStatus()); assertEquals(0, calls.get());
        select(draft("missing", true));
        VpnResult missing = query("192.0.2.1");
        assertEquals(ProviderVote.Status.UNKNOWN, missing.getStatus()); assertEquals(FailureReason.NO_PROVIDER, missing.getVotes().get(0).getReason());
        assertEquals("extension.missing", missing.getVotes().get(0).getProvider()); assertEquals(0, calls.get());
    }
    @Test void selectedProviderGetsNormalizedIpAndPreservesMetadataAndStableSourceId() throws Exception {
        ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "1", HASH, true), ip -> {
            assertEquals("192.0.2.1", ip);
            return CompletableFuture.completedFuture(DetectionObservation.positive(new DetectionMetadata(
                    Collections.singletonMap(DetectionMetadata.Type.TOR, true), 15169L, "Synthetic ISP", null, "JP", 80, null)));
        });
        select(draft("fixture", true)); VpnResult result = query("::ffff:192.0.2.1");
        assertTrue(result.isVpn()); assertEquals("extension.fixture", result.getVotes().get(0).getProvider());
        assertEquals(Boolean.TRUE, result.getVotes().get(0).getDetails().get(DetectionDetails.Type.TOR));
        assertEquals(Long.valueOf(15169), result.getVotes().get(0).getDetails().getAsn());
    }
    @Test void closedProviderCannotReuseCachedNegativeAndOldHandleCannotRemoveReplacement() throws Exception {
        cache = new SQLiteCacheProvider(directory.resolve("cache.db").toString()); ConnectionGuard.setCacheProvider(cache); assertTrue(cache.setup().get());
        AtomicInteger calls = new AtomicInteger();
        ProviderRegistration handle = ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "1", HASH, true), ip -> {
            calls.incrementAndGet(); return CompletableFuture.completedFuture(DetectionObservation.negative(DetectionMetadata.empty()));
        });
        ProviderConfiguration selected = draft("fixture", true); select(selected);
        assertEquals(ProviderVote.Status.NEGATIVE, query("192.0.2.1").getStatus());
        // Serialized cache worker barrier waits for the best-effort write before testing reuse.
        assertTrue(cache.getVpnResult("192.0.2.1").get().isPresent());
        assertTrue(query("192.0.2.1").isFromCache()); assertEquals(1, calls.get());
        handle.close(); assertFalse(handle.isRegistered());
        VpnResult closed = query("192.0.2.1");
        assertEquals(ProviderVote.Status.UNKNOWN, closed.getStatus()); assertFalse(closed.isFromCache());
        assertEquals(FailureReason.NO_PROVIDER, closed.getVotes().get(0).getReason());
        ProviderRegistration replacement = ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "2", HASH, true), ip -> CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty())));
        handle.close(); assertTrue(replacement.isRegistered());
        ProviderConfiguration changed = draft("fixture", true); assertNotEquals(selected.cacheNamespace, changed.cacheNamespace);
        select(changed); assertTrue(query("192.0.2.1").isVpn());
    }
    @Test void declaredFingerprintSeparatesConfigurationAndOpaqueCallbacksNeverSerialize() {
        ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "1", HASH, true), ip -> CompletableFuture.completedFuture(DetectionObservation.negative(DetectionMetadata.empty())));
        ProviderConfiguration first = draft("fixture", true); ExtensionRegistry.closeAll();
        String changed = String.join("", Collections.nCopies(64, "b"));
        ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "1", changed, true), ip -> CompletableFuture.completedFuture(DetectionObservation.negative(DetectionMetadata.empty())));
        ProviderConfiguration second = draft("fixture", true); assertNotEquals(first.cacheNamespace, second.cacheNamespace);
        String serialized = new com.google.gson.Gson().toJson(second.providers);
        assertFalse(serialized.contains("callback")); assertFalse(serialized.contains("registration")); assertFalse(serialized.contains("Lambda"));
    }
    @Test void nonVotingEvidenceCanInformRulesWithoutVotingOrLoweringQuorum() throws Exception {
        ConnectionGuardApi.registerProvider(new ProviderDescriptor("hosting", "1", HASH, false), ip -> CompletableFuture.completedFuture(
                new DetectionObservation(DetectionObservation.Status.UNKNOWN, DetectionObservation.Reason.NO_EVIDENCE,
                    new DetectionMetadata(Collections.singletonMap(DetectionMetadata.Type.HOSTING, true), null, null, null, null, null, null), 0, null)));
        select(draft("hosting", false)); VpnResult result = query("192.0.2.1");
        assertEquals(ProviderVote.Status.UNKNOWN, result.getStatus()); assertFalse(result.getVotes().get(0).isVoting());
        assertEquals(Boolean.TRUE, result.getVotes().get(0).getDetails().get(DetectionDetails.Type.HOSTING));
        assertThrows(IllegalArgumentException.class, () -> draft("hosting", true));
    }
    @Test void boundedRegistrySelectionAndQuorumRejectInvalidDrafts() {
        for (int i = 0; i < 8; i++) ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture" + i, "1", HASH, true), ip -> CompletableFuture.completedFuture(DetectionObservation.negative(DetectionMetadata.empty())));
        assertThrows(IllegalStateException.class, () -> ConnectionGuardApi.registerProvider(new ProviderDescriptor("extra", "1", HASH, true), ip -> null));
        assertThrows(IllegalArgumentException.class, () -> ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture0", "1", HASH, true), ip -> null));
        Map<String, Object> config = values("fixture0", true); config.put("required-positive-flags", 2);
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfiguration(config::get, Collections.emptyList()));
        config.put("required-positive-flags", 1);
        config.put("integrations.providers.sources", Collections.nCopies(9, Collections.singletonMap("id", "fixture0")));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionSettings(config::get));
        config.put("integrations.providers.sources", Arrays.asList(Collections.singletonMap("id", "fixture0"), Collections.singletonMap("id", "fixture0")));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionSettings(config::get));
    }
    @Test void closeDuringInflightQueryCannotPublishTheLatePositiveObservation() throws Exception {
        CompletableFuture<DetectionObservation> pending = new CompletableFuture<>(); CountDownLatch started = new CountDownLatch(1);
        ProviderRegistration handle = ConnectionGuardApi.registerProvider(new ProviderDescriptor("fixture", "1", HASH, true), ip -> { started.countDown(); return pending; });
        select(draft("fixture", true)); CompletableFuture<VpnResult> result = ConnectionGuard.getVpnResult("192.0.2.1");
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS)); handle.close(); pending.complete(DetectionObservation.positive(DetectionMetadata.empty()));
            assertEquals(ProviderVote.Status.UNKNOWN, result.get(2, TimeUnit.SECONDS).getStatus());
            assertEquals(FailureReason.NO_PROVIDER, result.get().getVotes().get(0).getReason());
        } finally { pending.complete(DetectionObservation.unknown(DetectionObservation.Reason.NO_PROVIDER)); }
    }
    @Test void repeatedQuiescentDraftActivationDoesNotRetainOldExtensionHealthHistory() {
        for (int i = 0; i < 30; i++) {
            String id = "fixture" + i;
            ConnectionGuardApi.registerProvider(new ProviderDescriptor(id, "1", HASH, true), ip -> CompletableFuture.completedFuture(DetectionObservation.negative(DetectionMetadata.empty())));
            ConnectionGuard.applyProviders(draft(id, true));
            assertEquals(Collections.singleton("extension." + id), ConnectionGuard.providerHealth().keySet());
            ExtensionRegistry.closeAll();
        }
    }
}
