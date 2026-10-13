package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.cache.MemoryCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.libs.okhttp3.HttpUrl;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned loopback HTTP and commands against the actual combined shaded JAR on Java 8. */
public final class ApiProfileJarCases {
    static final AtomicInteger requests=new AtomicInteger();
    static volatile CountDownLatch held,entered;
    static void require(boolean value){if(!value)throw new AssertionError("Actual-JAR profile case failed");}
    static void passed(String name){System.out.println("API_PROFILE_JAR_CASE "+name);}
    static void idle() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<end)Thread.sleep(2);
        require(ConnectionGuard.getLookupRuntime().isIdle());
    }
    static void restart() throws Exception {
        idle();ConnectionGuard.shutdown();LookupRuntime old=ConnectionGuard.getLookupRuntime();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<end){old.retireIfIdle();if(!old.isOpen()&&old.retireIfIdle()) break;Thread.sleep(2);}
        require(!old.isOpen()&&old.retireIfIdle());ConnectionGuard.configureLookup(LookupSettings.defaults());
    }
    static ProviderConfiguration apply(Map<String,Object> values) throws Exception {
        idle();ProviderConfiguration draft=new ProviderConfiguration(values::get,Collections.singletonList("fixture"));ConnectionGuard.applyProviders(draft);return draft;
    }
    static VpnResult vpn(String ip) throws Exception{return ConnectionGuard.getVpnResult(ip).get(3,TimeUnit.SECONDS);}
    public static void main(String[] args) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService worker=Executors.newCachedThreadPool();server.setExecutor(worker);
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            try {
                if(held!=null){entered.countDown();require(held.await(3,TimeUnit.SECONDS));}
                String path=exchange.getRequestURI().getPath();
                String text=path.startsWith("/v2/") ? "{\"status\":\"ok\",\""+path.substring(4)+"\":{\"proxy\":\"yes\",\"type\":\"VPN\",\"isocode\":\"PT\",\"provider\":\"Fixture ISP\",\"asn\":\"AS64496\"}}" : "{\"vpn\":true}";
                byte[] body=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException(error);}finally{exchange.close();}
        });server.start();
        Map<String,Object> values=new HashMap<>();values.put("provider.cache.type","Disabled");values.put("provider.geo.service","Disabled");
        values.put("provider.vpn.fixture.enabled",true);values.put("provider.vpn.fixture.request-url","http://127.0.0.1:"+server.getAddress().getPort()+"/fixture/%IP%");
        values.put("provider.vpn.fixture.response-format.is-vpn-field.field-name","vpn");
        try {
            ConnectionGuard.setCacheProvider(new MemoryCacheProvider());ConnectionGuard.setLogger(null);ProviderConfiguration original=apply(values);
            require(vpn("192.0.2.1").isVpn());require(vpn("192.0.2.1").isFromCache());require(requests.get()==1&&ConnectionGuard.apiBudgetStatus().reserved==1);
            passed("configured_default_and_fresh_cache_preserve_one_request");
            Object active=ConnectionGuard.getActiveDraft();List<String> preview=new ArrayList<>();
            require(OperationsCommands.handle(new String[]{"profile","show","budgeted-api","1"},p->true,preview::add));
            require(ConnectionGuard.getActiveDraft()==active&&ConnectionGuard.apiBudgetStatus().limit==0&&requests.get()==1);
            passed("preset_preview_is_read_only_and_spends_no_quota");
            values.put("lookup.api-daily-budget",1);require(original.cacheNamespace.equals(apply(values).cacheNamespace));
            require(vpn("192.0.2.1").isFromCache());require(vpn("192.0.2.2").getStatus()==ProviderVote.Status.UNKNOWN);require(requests.get()==1);
            ConnectionGuard.getCacheProvider().removeAllVpnResults().get();apply(values);require(vpn("192.0.2.3").getStatus()==ProviderVote.Status.UNKNOWN);
            require(ConnectionGuard.apiBudgetStatus().reserved==1);passed("reload_clear_and_lowered_cap_do_not_replenish_reservations");
            values.put("lookup.api-daily-budget",2);apply(values);
            ConnectionGuard.setGeoProvider(ip->CompletableFuture.completedFuture(Optional.of(new GeoResult(ip,"PT","","Fixture ISP"))));
            require(ConnectionGuard.getGeoLookup("192.0.2.1").get().getResult().isPresent());require(ConnectionGuard.apiBudgetStatus().reserved==2);
            passed("vpn_and_geo_share_the_same_daily_cap");
            values.put("lookup.api-policy","LOCAL_ONLY");require(!original.cacheNamespace.equals(apply(values).cacheNamespace));
            require(vpn("192.0.2.1").getStatus()==ProviderVote.Status.UNKNOWN&&requests.get()==1);
            ConnectionGuard.setGeoProvider(ip->{throw new AssertionError("Suppressed Geo source invoked");});
            require(ConnectionGuard.getGeoLookup("192.0.2.1").get().getReason()==FailureReason.NO_PROVIDER);
            passed("local_only_suppresses_http_geo_and_old_api_cache_facts");
            VpnProvider local=new VpnProvider(){public boolean isLocal(){return true;}public boolean stableSourceId(){return true;}public String sourceName(){return "fixture.local";}
                public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip){return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip,true)));}};
            VpnProvider remote=ConnectionGuard.getActiveDraft().providers.get(0);
            ConnectionGuard.setVpnProviders(new ArrayList<>(Arrays.asList(local,remote)));ConnectionGuard.setRequiredPositiveFlags(1);
            require(vpn("192.0.2.4").isVpn());require(ConnectionGuard.apiBudgetStatus().reserved==2);
            ConnectionGuard.setRequiredPositiveFlags(2);require(vpn("192.0.2.5").getStatus()==ProviderVote.Status.UNKNOWN);
            require(ConnectionGuard.getRequiredPositiveFlags()==2&&requests.get()==1);passed("local_evidence_survives_exhaustion_without_lowering_consensus");
            restart();ConnectionGuard.setCacheProvider(new MemoryCacheProvider());values.put("lookup.api-policy","CONFIGURED");values.put("lookup.api-daily-budget",1);apply(values);
            ProxyCheckClient client=new ProxyCheckClient("synthetic-key",HttpUrl.get("http://127.0.0.1:"+server.getAddress().getPort()+"/v2/"));
            ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new ProxyCheckVpnProvider("synthetic-key",false,client))));
            ConnectionGuard.setRequiredPositiveFlags(1);ConnectionGuard.setGeoProvider(new ProxyCheckGeoProvider("synthetic-key",false,client));
            held=new CountDownLatch(1);entered=new CountDownLatch(1);int before=requests.get();
            List<CompletableFuture<VpnResult>> vpn=new ArrayList<>();List<CompletableFuture<GeoLookup>> geo=new ArrayList<>();
            for(int i=0;i<100;i++){vpn.add(ConnectionGuard.getVpnResult("192.0.2.6"));geo.add(ConnectionGuard.getGeoLookup("192.0.2.6"));}
            require(entered.await(1,TimeUnit.SECONDS));held.countDown();
            for(CompletableFuture<VpnResult> result:vpn)require(result.get(3,TimeUnit.SECONDS).isVpn());
            for(CompletableFuture<GeoLookup> result:geo)require(result.get(3,TimeUnit.SECONDS).getResult().isPresent());
            require(requests.get()==before+1&&ConnectionGuard.apiBudgetStatus().reserved==1&&ConnectionGuard.providerHealth().get("ProxyCheck").snapshot().dailyUsed==1);
            passed("one_hundred_dual_scope_callers_share_one_http_and_two_quota_layers");
        }finally{
            if(held!=null)held.countDown();idle();ConnectionGuard.shutdown();server.stop(0);worker.shutdownNow();require(worker.awaitTermination(3,TimeUnit.SECONDS));
        }
    }
}
