package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine local list/MMDB adapters remain eligible with an exhausted shared cap. */
class LocalApiBudgetRoutingTest {
    private void retire() throws Exception {
        LookupRuntime prior=ConnectionGuard.getLookupRuntime();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<end){if(prior.retireIfIdle()&&!prior.isOpen())return;Thread.sleep(2);}fail("Owned transport did not retire.");
    }
    private void idle() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<end)Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
    }
    @Test void freshListAndStaleMmdbRetainTheirOwnEvidenceRatherThanApiBudgetFailure() throws Exception {
        retire();ConnectionGuard.shutdown();ConnectionGuard.configureLookup(LookupSettings.defaults());
        java.lang.reflect.Field signature=ConnectionGuard.class.getDeclaredField("activeCacheSignature");signature.setAccessible(true);
        Object previous=signature.get(null);signature.set(null,null);
        try {
            ConnectionGuard.setCacheProvider(new NoCacheProvider());ConnectionGuard.setLogger(null);
            Map<String,Object> values=new HashMap<>();values.put("provider.cache.type","Disabled");values.put("provider.geo.service","Disabled");values.put("lookup.api-daily-budget",1);
            ConnectionGuard.applyProviders(new ProviderConfiguration(values::get,Collections.emptyList()));
            ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip->CompletableFuture.completedFuture(Optional.of(new VpnResult(ip,true))))));
            assertTrue(ConnectionGuard.getVpnResult("192.0.2.1").get().isVpn());assertEquals(1,ConnectionGuard.apiBudgetStatus().reserved);
            long now=System.currentTimeMillis();LocalSource list=new LocalSource("fixture",LocalSource.Kind.VPN,"Owned synthetic list","MIT","Synthetic documentation addresses",24,null);
            LocalSnapshot fresh=LocalSnapshot.parse(list,"192.0.2.0/24\n".getBytes(StandardCharsets.UTF_8),now,now,"IMPORT_AS_OF",now);
            LocalSource geo=new LocalSource("geo",LocalSource.Kind.GEO,"Official MaxMind synthetic test fixture","MIT","Test fixture; stale intentionally",24,null);
            LocalSnapshot stale=LocalSnapshot.parse(geo,BoundedMmdbTest.fixture("GeoIP2-Country-Test.mmdb"),now,now,"MMDB_BUILD",now);
            assertEquals(FailureReason.STALE_DATA,stale.readiness(now));
            for(String policy:Arrays.asList("CONFIGURED","LOCAL_ONLY")) {
                idle();values.put("lookup.api-policy",policy);ConnectionGuard.applyProviders(new ProviderConfiguration(values::get,Collections.emptyList()));
                ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new LocalVpnProvider(fresh))));
                ConnectionGuard.setGeoProvider(new LocalGeoProvider(stale,null));
                assertTrue(ConnectionGuard.getVpnResult("192.0.2.2").get().isVpn());
                assertEquals(FailureReason.STALE_DATA,ConnectionGuard.getGeoLookup("81.2.69.160").get().getReason());
                assertEquals(1,ConnectionGuard.apiBudgetStatus().reserved);
            }
        } finally {
            idle();ConnectionGuard.shutdown();retire();ConnectionGuard.configureLookup(LookupSettings.defaults());signature.set(null,previous);
        }
    }
}
