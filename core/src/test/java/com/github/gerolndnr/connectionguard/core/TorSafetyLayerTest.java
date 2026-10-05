package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.cache.MemoryCacheProvider;
import com.github.gerolndnr.connectionguard.core.local.TorExitList;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TorSafetyLayerTest {
    @TempDir Path directory;
    @AfterEach void reset() { ConnectionGuard.setVpnProviders(new ArrayList<>()); }
    @Test void cachedAllowAndAllFourApiErrorsCannotBypassTor() throws Exception {
        StringBuilder body = new StringBuilder("# fetched-at=" + System.currentTimeMillis() + "\n");
        for (int i = 1; i <= 100; i++) body.append("2001:db8::").append(Integer.toHexString(i)).append('\n');
        Files.write(directory.resolve("tor-exits.txt"), body.toString().getBytes(StandardCharsets.UTF_8));
        java.lang.reflect.Field field = ConnectionGuard.class.getDeclaredField("tor"); field.setAccessible(true);
        for (FailureReason fault : Arrays.asList(FailureReason.TIMEOUT, FailureReason.RATE_LIMIT, FailureReason.INVALID_RESPONSE, FailureReason.NETWORK)) {
            AtomicInteger requests = new AtomicInteger(); MemoryCacheProvider cache = new MemoryCacheProvider();
            cache.addVpnResult(new VpnResult("2001:db8:0:0:0:0:0:1", false)).get(); ConnectionGuard.setCacheProvider(cache);
            ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> {
                requests.incrementAndGet(); CompletableFuture<Optional<VpnResult>> failed = new CompletableFuture<>(); failed.completeExceptionally(new LookupException(fault)); return failed;
            })));
            field.set(null, new TorExitList(directory, Logger.getAnonymousLogger()));
            VpnResult result = ConnectionGuard.getVpnResult("2001:db8::1").get(100, TimeUnit.MILLISECONDS);
            assertTrue(result.isVpn()); assertEquals("TorExitList", result.getVotes().get(0).getProvider()); assertEquals(0, requests.get());
        }
    }
}
