package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.cache.MemoryCacheProvider;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.libs.com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bound actual-JAR managed order contract; owned local HTTP/file operations only. */
public final class DashboardOrderJarCases {
    static void require(boolean ok){if(!ok)throw new AssertionError("Owned dashboard-order contract failed");}
    static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    static void passed(String name){System.out.println("DASHBOARD_ORDER_JAR_CASE "+name);}
    static void idle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<end)Thread.sleep(2);require(ConnectionGuard.getLookupRuntime().isIdle());}
    public static void main(String[] args)throws Exception {
        Path directory=Paths.get(args[0]);Files.createDirectory(directory);
        JsonObject snapshot=CloudManagedConfig.snapshot(k->null);
        require(snapshot.get("provider.vpn-failover.order").equals(new Gson().toJsonTree(VpnFailoverSettings.TEMPLATE_ORDER)));
        require(snapshot.has("provider.vpn-failover.enabled")&&snapshot.has("provider.max-external-attempts")&&snapshot.has("provider.vpn.ipquery.enabled")&&snapshot.has("provider.vpn.ipqualityscore.api-key"));
        require(!snapshot.get("provider.vpn.ipquery.enabled").getAsBoolean());passed("snapshot_unlocks_existing_gated_controls_without_enabling_recipients");
        Map<String,Object> basic=new HashMap<>();basic.put("provider.geo.service","Disabled");for(String id:new String[]{"proxycheck","zowi","ipquery","ip-api"})basic.put("provider.vpn."+id+".enabled",true);
        basic.putAll(CloudManagedConfig.parse(json("{\"provider.vpn-failover.order\":[\"ip-api\",\"zowi\"],\"provider.max-external-attempts\":3}")));
        ProviderConfiguration partial=new ProviderConfiguration(basic::get,Arrays.asList("proxycheck","zowi","ipquery","ip-api"));
        require(partial.keys.equals(Arrays.asList("zowi","proxycheck","ipquery","ip-api"))&&partial.externalAttempts==3);passed("partial_order_retains_unlisted_sources_and_ipapi_last");
        Map<String,Object> unknown=new HashMap<>(basic);unknown.put("provider.vpn-failover.order",Collections.singletonList("unknown"));
        boolean refused=false;try{new ProviderConfiguration(unknown::get,Arrays.asList("proxycheck","zowi","ipquery","ip-api"));}catch(VpnFailoverSettings.InvalidOrder e){refused=e.getMessage().contains("unknown or duplicate source IDs");}require(refused);passed("unknown_source_is_refused_without_a_silent_drop");
        require(CloudManagedConfig.parse(json("{\"provider.vpn-failover.order\":[]}")).get("provider.vpn-failover.order").equals(Collections.emptyList()));
        require(CloudManagedConfig.snapshot(k->k.equals("provider.vpn-failover.order")?Collections.emptyList():null).getAsJsonArray("provider.vpn-failover.order").size()==0);passed("empty_configured_order_is_valid_and_reported_exactly");
        Map<String,Object> secret=CloudManagedConfig.parse(json("{\"provider.vpn.ipqualityscore.enabled\":true,\"provider.vpn.ipqualityscore.api-key\":\"owned-private-fixture-1234\"}"));
        JsonObject hidden=CloudManagedConfig.snapshot(secret::get);require(!hidden.toString().contains("owned-private-fixture")&&hidden.getAsJsonObject("provider.vpn.ipqualityscore.api-key").get("hint").getAsString().equals("1234"));passed("optional_ipqualityscore_key_snapshot_uses_existing_redaction");
        AtomicInteger clean=new AtomicInteger(),block=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{boolean deny=e.getRequestURI().getPath().contains("block");(deny?block:clean).incrementAndGet();byte[] data=("{\"vpn\":"+deny+"}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
        ConnectionGuard.setLogger(null);ConnectionGuard.setCacheProvider(new MemoryCacheProvider());
        try {
            Map<String,Object> local=new HashMap<>();local.put("provider.geo.service","Disabled");local.put("provider.cache.type","Memory");local.put("provider.vpn-failover.order",Arrays.asList("clean","block"));
            for(String id:new String[]{"clean","block"}){String p="provider.vpn."+id+".";local.put(p+"enabled",true);local.put(p+"request-url","http://127.0.0.1:"+server.getAddress().getPort()+"/"+id+"/%IP%");local.put(p+"response-format.is-vpn-field.field-name","vpn");}
            ProviderConfiguration first=new ProviderConfiguration(local::get,Arrays.asList("clean","block"));ConnectionGuard.applyProviders(first);
            require(ConnectionGuard.getVpnResult("192.0.2.9").get(3,TimeUnit.SECONDS).getStatus()==ProviderVote.Status.NEGATIVE);require(ConnectionGuard.getVpnResult("192.0.2.9").get().isFromCache());idle();
            CloudManagedConfig selected=CloudManagedConfig.next(CloudManagedConfig.EMPTY,1,false,json("{\"provider.vpn-failover.order\":[\"block\"]}"),null);CloudManagedConfig.write(directory,selected);
            Map<String,Object> overlay=new HashMap<>(local);CloudManagedConfig.overlay(directory,overlay::put);ProviderConfiguration next=new ProviderConfiguration(overlay::get,Arrays.asList("clean","block"));ConnectionGuard.applyProviders(next);
            require(!first.cacheNamespace.equals(next.cacheNamespace));require(ConnectionGuard.getVpnResult("192.0.2.9").get(3,TimeUnit.SECONDS).isVpn());require(clean.get()==1&&block.get()==1);idle();
            Map<String,Object> reload=new HashMap<>(local);CloudManagedConfig.overlay(directory,reload::put);require(reload.get("provider.vpn-failover.order").equals(Collections.singletonList("block")));require(local.get("provider.vpn-failover.order").equals(Arrays.asList("clean","block")));
            passed("managed_reload_rebuilds_cache_namespace_and_preserves_local_config");
        } finally {idle();server.stop(0);ConnectionGuard.shutdown();}
    }
}
