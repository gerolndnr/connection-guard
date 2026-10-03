package com.github.gerolndnr.connectionguard.core.luckperms;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.lookup.LookupSettings;
import net.luckperms.api.*;
import net.luckperms.api.cacheddata.*;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.*;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class CGLuckPermsHelperTest {
    final UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    final QueryOptions staticOptions = mock(QueryOptions.class, (method, args) -> null);
    final QueryOptions playerOptions = mock(QueryOptions.class, (method, args) -> null);
    Object subject = new Object();
    boolean loaded = true;
    boolean grant = true;
    CompletableFuture<User> loading;
    final AtomicInteger loads = new AtomicInteger(), cleanups = new AtomicInteger();
    User user;
    LuckPerms api;
    @BeforeEach void setup() throws Exception {
        resetLookupAfterQuiescence();
        CachedPermissionData data = mock(CachedPermissionData.class, (method, args) -> {
            if (method.equals("checkPermission")) return grant ? Tristate.TRUE : Tristate.FALSE;
            return null;
        });
        CachedDataManager cached = mock(CachedDataManager.class, (method, args) -> {
            if (method.equals("getPermissionData")) {
                assertSame(subject == null ? staticOptions : playerOptions, args[0]);
                return data;
            }
            return null;
        });
        user = mock(User.class, (method, args) -> method.equals("getCachedData") ? cached : null);
        loading = CompletableFuture.completedFuture(user);
        UserManager manager = mock(UserManager.class, (method, args) -> {
            if (method.equals("getUser")) return loaded ? user : null;
            if (method.equals("loadUser")) { loads.incrementAndGet(); return loading; }
            if (method.equals("cleanupUser")) cleanups.incrementAndGet();
            return null;
        });
        ContextManager context = mock(ContextManager.class, (method, args) -> {
            if (method.equals("getStaticQueryOptions")) return staticOptions;
            if (method.equals("getQueryOptions")) return args[0] instanceof User ? Optional.empty() : playerOptions;
            return null;
        });
        api = mock(LuckPerms.class, (method, args) -> method.equals("getUserManager") ? manager : method.equals("getContextManager") ? context : null);
    }
    @AfterEach void cleanup() throws Exception {
        if (loading != null && user != null) loading.complete(user);
        resetLookupAfterQuiescence();
    }
    private static void resetLookupAfterQuiescence() throws InterruptedException {
        // Result completion is not physical quiescence. Retire atomically, then wait
        // for terminated pools before resetting this shared test fixture's limits.
        com.github.gerolndnr.connectionguard.core.lookup.LookupRuntime runtime = ConnectionGuard.getLookupRuntime();
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!runtime.retireIfIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertFalse(runtime.isOpen(), "Previous fixture left lookup work or deadlines active.");
        while (!runtime.retireIfIdle() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(runtime.retireIfIdle(), "Previous fixture's lookup pools did not terminate.");
        ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    @SuppressWarnings("unchecked") static <T> T mock(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> handler.apply(method.getName(), args));
    }
    @Test void loadedUserUsesSubjectContextWithoutReloading() throws Exception {
        assertTrue(CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject).get());
        assertEquals(0, loads.get()); assertEquals(0, cleanups.get());
    }
    @Test void offlineUserUsesStaticContextAndIsCleanedUp() throws Exception {
        loaded = false; subject = null;
        assertTrue(CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", null).get());
        assertEquals(1, loads.get()); assertEquals(1, cleanups.get());
    }
    @Test void deniedPermissionCannotSkipChecks() throws Exception {
        grant = false;
        assertFalse(CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject).get());
    }
    @Test void missingUuidAndUninstalledLuckPermsNeverThrowOrGrant() throws Exception {
        assertFalse(CGLuckPermsHelper.hasPermission(null, "connectionguard.exemption.vpn").get());
        assertFalse(CGLuckPermsHelper.hasPermission(id, "connectionguard.exemption.vpn").get());
    }
    @Test void stalledUserLoadIsBoundedAndLaterCompletionCannotGrant() throws Exception {
        loaded = false; loading = new CompletableFuture<>();
        CompletableFuture<Boolean> result = CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject);
        assertFalse(result.get(2, TimeUnit.SECONDS));
        loading.complete(user);
        assertFalse(result.get()); assertEquals(1, cleanups.get());
    }
    @Test void unchangedLimitReloadPreservesPendingPermissionDeadline() throws Exception {
        loaded = false; loading = new CompletableFuture<>();
        CompletableFuture<Boolean> result = CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject);
        ConnectionGuard.configureLookup(LookupSettings.defaults());
        assertFalse(result.get(2, TimeUnit.SECONDS), "Reload must not cancel the only permission timeout.");
        loading.complete(user); assertFalse(result.get()); assertEquals(1, cleanups.get());
    }
    @Test void changedLimitsCannotRetirePendingPermissionDeadline() throws Exception {
        loaded = false; loading = new CompletableFuture<>();
        CompletableFuture<Boolean> result = CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject);
        Object previous = ConnectionGuard.getLookupRuntime();
        try {
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.configureLookup(
                    new LookupSettings(4000, 2000, 4, 32, 64, 3, 30000)));
            assertSame(previous, ConnectionGuard.getLookupRuntime());
            assertFalse(result.get(2, TimeUnit.SECONDS));
        } finally { loading.complete(user); }
    }
    @Test void providerActivationCannotChangePolicyWhilePermissionDeadlineIsPending() throws Exception {
        com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration draft =
                new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(
                        key -> key.equals("provider.geo.service") ? "Disabled" : null, Collections.emptyList());
        Object previous = ConnectionGuard.getActiveDraft();
        loaded = false; loading = new CompletableFuture<>();
        CompletableFuture<Boolean> result = CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject);
        try {
            assertThrows(IllegalStateException.class, () -> ConnectionGuard.applyProviders(draft));
            assertSame(previous, ConnectionGuard.getActiveDraft());
            assertFalse(result.get(2, TimeUnit.SECONDS));
        } finally { loading.complete(user); }
    }
    @Test void loginDeadlineClipsNativeUserLoadWaitAndIgnoresALaterGrant() throws Exception {
        ConnectionGuard.configureLookup(new LookupSettings(200, 100, 2, 8, 8, 100, 100));
        loaded = false; loading = new CompletableFuture<>();
        com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.Result result =
                com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.check("192.0.2.211",
                        ConnectionGuard.getLookupRuntime().getSettings(), System.nanoTime(), false,
                        com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.Permission.lookup(() -> CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject)),
                        com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.Permission.known(true)).get(1, TimeUnit.SECONDS);
        assertTrue(result.isExpired()); assertFalse(result.vpnExempt()); assertEquals(1, loads.get());
        loading.complete(user); assertEquals(1, cleanups.get()); assertFalse(result.vpnExempt());
    }
    @Test void failedUserLoadIsSafelyDenied() throws Exception {
        loaded = false; loading = new CompletableFuture<>(); loading.completeExceptionally(new IllegalStateException("sensitive database URL"));
        assertFalse(CGLuckPermsHelper.check(api, id, "connectionguard.exemption.vpn", subject).get());
    }
}
