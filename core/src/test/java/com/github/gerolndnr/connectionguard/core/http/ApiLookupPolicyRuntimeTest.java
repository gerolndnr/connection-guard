package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.cache.*;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.geo.ProxyCheckGeoProvider;
import com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Owned HTTP and actual core routing, no external provider or account. */
@Timeout(10)
class ApiLookupPolicyRuntimeTest {
    private HttpServer server;
    private ExecutorService http;
    private final AtomicInteger requests=new AtomicInteger(), geoCalls=new AtomicInteger();
    private final Map<String,Object> values=new HashMap<>();
    private volatile CountDownLatch held,entered;
    private java.lang.reflect.Field cacheSignature;
    private Object previousCacheSignature;
    private void idle() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<end) Thread.sleep(2);
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
    }
    private void retire() throws Exception {
        LookupRuntime prior=ConnectionGuard.getLookupRuntime();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<end){prior.retireIfIdle();if(!prior.isOpen()&&prior.retireIfIdle()) return;Thread.sleep(2);}
        fail("Previous owned fixture transport did not physically terminate.");
    }
    @BeforeEach void setup() throws Exception {
        retire(); ConnectionGuard.shutdown(); ConnectionGuard.configureLookup(LookupSettings.defaults());
        // This fixture emulates a fresh startup; do not weaken the live cache-reload guard.
        cacheSignature=ConnectionGuard.class.getDeclaredField("activeCacheSignature");cacheSignature.setAccessible(true);
        previousCacheSignature=cacheSignature.get(null);cacheSignature.set(null,null);
        ConnectionGuard.setCacheProvider(new NoCacheProvider()); ConnectionGuard.setVpnProviders(new ArrayList<>()); ConnectionGuard.setLogger(null);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); http=Executors.newCachedThreadPool(); server.setExecutor(http);
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            if(held!=null) { entered.countDown();try { if(!held.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Owned HTTP not released"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);} }
            String path=exchange.getRequestURI().getPath();
            String response=path.startsWith("/v2/") ? "{\"status\":\"ok\",\""+path.substring(4)+"\":{\"proxy\":\"yes\",\"type\":\"VPN\",\"isocode\":\"PT\",\"provider\":\"Fixture ISP\",\"asn\":\"AS64496\"}}" : "{\"vpn\":true}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        values.put("provider.cache.type","Disabled"); values.put("provider.geo.service","Disabled");
        remote("remote");
    }
    private void remote(String id) {
        String base="provider.vpn."+id+".";
        values.put(base+"enabled",true); values.put(base+"request-url","http://127.0.0.1:"+server.getAddress().getPort()+"/"+id+"/%IP%");
        values.put(base+"response-format.is-vpn-field.field-name","vpn");
    }
    private ProviderConfiguration apply(String... keys) throws Exception {
        idle(); ProviderConfiguration draft=new ProviderConfiguration(values::get,Arrays.asList(keys)); ConnectionGuard.applyProviders(draft); return draft;
    }
    private VpnResult lookup(String ip) throws Exception { return ConnectionGuard.getVpnResult(ip).get(3,TimeUnit.SECONDS); }
    @AfterEach void close() throws Exception {
        try {
            if(held!=null) held.countDown();idle();server.stop(0);http.shutdownNow();assertTrue(http.awaitTermination(2,TimeUnit.SECONDS));
            ConnectionGuard.shutdown();retire();ConnectionGuard.configureLookup(LookupSettings.defaults());ConnectionGuard.setCacheProvider(new NoCacheProvider());
        } finally { if(cacheSignature!=null) cacheSignature.set(null,previousCacheSignature); }
    }
    @Test void globalCapIsSharedAcrossVpnAndGeoAndPreservedByReloadAndClear() throws Exception {
        values.put("lookup.api-daily-budget",2); apply("remote");
        ConnectionGuard.setGeoProvider(ip->{geoCalls.incrementAndGet();return CompletableFuture.completedFuture(Optional.of(new GeoResult(ip,"PT","","Fixture ISP")));});
        assertTrue(lookup("192.0.2.1").isVpn()); assertTrue(ConnectionGuard.getGeoLookup("192.0.2.1").get().getResult().isPresent());
        assertEquals(2,ConnectionGuard.apiBudgetStatus().reserved);
        assertEquals(ProviderVote.Status.UNKNOWN,lookup("192.0.2.2").getStatus());
        assertEquals(FailureReason.BUDGET_EXHAUSTED,ConnectionGuard.getGeoLookup("192.0.2.2").get().getReason());
        ConnectionGuard.getCacheProvider().removeAllVpnResults().get(); apply("remote");
        assertEquals(ProviderVote.Status.UNKNOWN,lookup("192.0.2.3").getStatus());
        assertEquals(1,requests.get());assertEquals(1,geoCalls.get());assertEquals(2,ConnectionGuard.apiBudgetStatus().reserved);
        assertTrue(ConnectionGuard.apiLookupStatus().contains("limit=2"));
    }
    @Test void localOnlySuppressesConfiguredHttpAndGeoWithoutCallingThemOrInventingNegative() throws Exception {
        values.put("lookup.api-policy","LOCAL_ONLY"); apply("remote");
        ConnectionGuard.setGeoProvider(ip->{geoCalls.incrementAndGet();return CompletableFuture.completedFuture(Optional.of(new GeoResult(ip,"PT","","Fixture ISP")));});
        VpnResult result=lookup("192.0.2.1");
        assertEquals(ProviderVote.Status.UNKNOWN,result.getStatus()); assertEquals(FailureReason.NO_PROVIDER,result.getVotes().get(0).getReason());
        assertEquals(FailureReason.NO_PROVIDER,ConnectionGuard.getGeoLookup("192.0.2.1").get().getReason());
        assertEquals(0,requests.get());assertEquals(0,geoCalls.get());assertEquals(0,ConnectionGuard.apiBudgetStatus().reserved);
    }
    private VpnProvider local() {
        return new VpnProvider() {
            public boolean isLocal(){return true;} public String sourceName(){return "fixture.local";} public boolean stableSourceId(){return true;}
            public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip){return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip,true)));}
        };
    }
    @Test void localEvidenceIsStillUsedWhenBudgetIsEmptyAndLocalOnlyKeepsConsensusMinimum() throws Exception {
        values.put("lookup.api-daily-budget",1); ProviderConfiguration draft=apply("remote");
        assertTrue(lookup("192.0.2.1").isVpn());
        ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(local(),draft.providers.get(0)))); ConnectionGuard.setFailover(true,3);
        assertTrue(lookup("192.0.2.2").isVpn()); assertEquals(1,requests.get());assertEquals(1,ConnectionGuard.apiBudgetStatus().reserved);
        remote("second"); values.put("lookup.api-policy","LOCAL_ONLY");values.put("provider.vpn-failover.enabled",false);values.put("required-positive-flags",2);
        draft=apply("remote","second"); ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(local(),draft.providers.get(0))));
        assertEquals(ProviderVote.Status.UNKNOWN,lookup("192.0.2.3").getStatus());assertEquals(2,ConnectionGuard.getRequiredPositiveFlags());
        assertEquals(1,requests.get());assertEquals(1,ConnectionGuard.apiBudgetStatus().reserved);
    }
    @Test void freshCacheDoesNotSpendBudgetAndLocalOnlyCannotReuseApiEvidence() throws Exception {
        ConnectionGuard.setCacheProvider(new MemoryCacheProvider());values.put("lookup.api-daily-budget",1);
        ProviderConfiguration first=apply("remote");assertTrue(lookup("192.0.2.1").isVpn());assertTrue(lookup("192.0.2.1").isFromCache());
        assertEquals(1,requests.get());assertEquals(1,ConnectionGuard.apiBudgetStatus().reserved);
        values.put("lookup.api-daily-budget",2);assertEquals(first.cacheNamespace,apply("remote").cacheNamespace);
        assertTrue(lookup("192.0.2.1").isFromCache());assertEquals(1,requests.get());
        values.put("lookup.api-policy","LOCAL_ONLY");assertNotEquals(first.cacheNamespace,apply("remote").cacheNamespace);
        assertEquals(ProviderVote.Status.UNKNOWN,lookup("192.0.2.1").getStatus());assertEquals(1,requests.get());
        values.put("lookup.api-policy","CONFIGURED");values.put("lookup.api-daily-budget",1);apply("remote");
        assertEquals(ProviderVote.Status.UNKNOWN,lookup("192.0.2.2").getStatus());assertEquals(1,requests.get());
    }
    @Test void invalidPolicyOrBudgetRejectsBeforeChangingTheActiveDraft() throws Exception {
        ProviderConfiguration active=apply("remote");
        values.put("lookup.api-policy","TYPO");assertThrows(IllegalArgumentException.class,()->new ProviderConfiguration(values::get,Collections.singletonList("remote")));
        values.put("lookup.api-policy","CONFIGURED");values.put("lookup.api-daily-budget",-1);
        assertThrows(IllegalArgumentException.class,()->new ProviderConfiguration(values::get,Collections.singletonList("remote")));
        assertSame(active,ConnectionGuard.getActiveDraft());assertTrue(lookup("192.0.2.1").isVpn());assertEquals(1,requests.get());
    }
    @Test void sharedProxyCheckDualScopeStampedeConsumesOneGlobalAndSourceReservation() throws Exception {
        values.put("lookup.api-daily-budget",1);apply("remote");
        ProxyCheckClient client=new ProxyCheckClient("synthetic-key",HttpUrl.get("http://127.0.0.1:"+server.getAddress().getPort()+"/v2/"));
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new ProxyCheckVpnProvider("synthetic-key",false,client))));
        ConnectionGuard.setGeoProvider(new ProxyCheckGeoProvider("synthetic-key",false,client));
        held=new CountDownLatch(1);entered=new CountDownLatch(1);
        List<CompletableFuture<VpnResult>> vpn=new ArrayList<>();List<CompletableFuture<GeoLookup>> geo=new ArrayList<>();
        for(int i=0;i<100;i++){vpn.add(ConnectionGuard.getVpnResult("192.0.2.1"));geo.add(ConnectionGuard.getGeoLookup("192.0.2.1"));}
        assertTrue(entered.await(1,TimeUnit.SECONDS));held.countDown();
        for(CompletableFuture<VpnResult> result:vpn) assertTrue(result.get(3,TimeUnit.SECONDS).isVpn());
        for(CompletableFuture<GeoLookup> result:geo) assertEquals("PT",result.get(3,TimeUnit.SECONDS).getResult().get().getCountryName());
        assertEquals(1,requests.get());assertEquals(1,ConnectionGuard.apiBudgetStatus().reserved);
        assertEquals(1,ConnectionGuard.providerHealth().get("ProxyCheck").snapshot().dailyUsed);
        assertEquals(ProviderVote.Status.UNKNOWN,lookup("192.0.2.2").getStatus());assertEquals(1,requests.get());
    }
}
