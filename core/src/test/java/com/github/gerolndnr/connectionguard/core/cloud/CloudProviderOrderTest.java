package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.*;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CloudProviderOrderTest {
    @TempDir Path directory;
    java.lang.reflect.Field signature;Object priorSignature;
    void retire() throws Exception {
        LookupRuntime old=ConnectionGuard.getLookupRuntime();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end){old.retireIfIdle();if(!old.isOpen()&&old.retireIfIdle())return;Thread.sleep(2);}
        fail("Owned runtime did not physically retire");
    }
    void idle() throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(ConnectionGuard.getLookupRuntime().isIdle());}
    @BeforeEach void setup() throws Exception {
        ConnectionGuard.shutdown();retire();ConnectionGuard.configureLookup(LookupSettings.defaults());ConnectionGuard.applySettings(GuardSettings.defaults());
        signature=ConnectionGuard.class.getDeclaredField("activeCacheSignature");signature.setAccessible(true);priorSignature=signature.get(null);signature.set(null,null);
        ConnectionGuard.setLogger(null);ConnectionGuard.setCacheProvider(new NoCacheProvider());
    }
    @AfterEach void cleanup() throws Exception {
        try {idle();ConnectionGuard.shutdown();retire();ConnectionGuard.configureLookup(LookupSettings.defaults());ConnectionGuard.setCacheProvider(new NoCacheProvider());}
        finally {signature.set(null,priorSignature);}
    }
    JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    @Test void snapshotUnlocksControlsWithTemplateFallbackButDoesNotEnableAbsentRecipients() {
        JsonObject snapshot=CloudManagedConfig.snapshot(k->null);
        Map<String,Object> shipped=new org.yaml.snakeyaml.Yaml().load(getClass().getResourceAsStream("/config.yml"));
        assertEquals(VpnFailoverSettings.TEMPLATE_ORDER,((Map<?,?>)((Map<?,?>)shipped.get("provider")).get("vpn-failover")).get("order"));
        assertEquals(new Gson().toJsonTree(VpnFailoverSettings.TEMPLATE_ORDER),snapshot.get("provider.vpn-failover.order"));
        assertTrue(snapshot.get("provider.vpn-failover.enabled").getAsBoolean());assertEquals(16,snapshot.get("provider.max-external-attempts").getAsInt());
        assertFalse(snapshot.get("provider.vpn.ipquery.enabled").getAsBoolean());assertFalse(snapshot.get("provider.vpn.ipqualityscore.enabled").getAsBoolean());
        assertFalse(snapshot.getAsJsonObject("provider.vpn.ipqualityscore.api-key").get("set").getAsBoolean());
        assertEquals(Boolean.FALSE,CloudManagedConfig.snapshot(k->k.equals("provider.vpn-strategy")?"CONSENSUS":null).get("provider.vpn-failover.enabled").getAsBoolean());
    }
    @Test void configuredEmptyAndLongLegacyOrderAreReportedExactlyWithoutTruncation() {
        assertEquals(0,CloudManagedConfig.snapshot(k->k.equals("provider.vpn-failover.order")?Collections.emptyList():null).getAsJsonArray("provider.vpn-failover.order").size());
        String legacy="extension."+String.join("",Collections.nCopies(64,"x"));
        assertEquals(legacy,CloudManagedConfig.snapshot(k->k.equals("provider.vpn-failover.order")?Collections.singletonList(legacy):null).getAsJsonArray("provider.vpn-failover.order").get(0).getAsString());
    }
    @Test void remoteOrderAndSwitchesAreStrictAndBounded() {
        for(String raw:new String[]{"null","true","[3]","[\"zowi\",\"zowi\"]","[\"local\"]","[\"connectionguard-intel\"]","[\"tor\"]","[\"bad/id\"]"})
            assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(json("{\"provider.vpn-failover.order\":"+raw+"}")));
        JsonObject oversized=new JsonObject();JsonArray ids=new JsonArray();for(int i=0;i<17;i++)ids.add("source"+i);oversized.add("provider.vpn-failover.order",ids);
        assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(oversized));
        ids=new JsonArray();ids.add(String.join("",Collections.nCopies(65,"x")));oversized.add("provider.vpn-failover.order",ids);assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(oversized));
        for(String value:new String[]{"0","17","1.5","\"2\""})assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(json("{\"provider.max-external-attempts\":"+value+"}")));
        for(String path:new String[]{"provider.vpn-failover.enabled","provider.vpn.ipquery.enabled","provider.vpn.ipqualityscore.enabled"})
            assertThrows(IllegalArgumentException.class,()->CloudManagedConfig.parse(json("{\""+path+"\":\"true\"}")));
    }
    @Test void partialManagedOrderRetainsLocalFirstIpApiLastAndPersistsAcrossReload() throws Exception {
        Map<String,Object> base=new HashMap<>();base.put("provider.geo.service","Disabled");base.put("provider.local.connectionguard-intel.enabled",true);
        for(String id:new String[]{"proxycheck","zowi","ipquery","ip-api"})base.put("provider.vpn."+id+".enabled",true);
        base.put("provider.vpn.ipquery.enabled",false);
        List<String> keys=Arrays.asList("proxycheck","ipquery","ip-api","zowi");
        CloudManagedConfig next=CloudManagedConfig.next(CloudManagedConfig.EMPTY,1,false,json("{\"provider.vpn-failover.order\":[\"ip-api\",\"zowi\"],\"provider.vpn-failover.enabled\":true,\"provider.max-external-attempts\":3,\"provider.vpn.ipquery.enabled\":true}"),null);
        CloudManagedConfig.write(directory,next);Map<String,Object> effective=new HashMap<>(base);CloudManagedConfig.overlay(directory,effective::put);
        ProviderConfiguration first=new ProviderConfiguration(effective::get,keys,directory.toRealPath());ConnectionGuard.applyProviders(first);
        assertEquals(Arrays.asList("connectionguard-intel","zowi","proxycheck","ipquery","ip-api"),first.keys);assertEquals(3,first.externalAttempts);
        assertEquals(Arrays.asList("ip-api","zowi"),CloudManagedConfig.load(directory).values.get("provider.vpn-failover.order"));
        Map<String,Object> reloaded=new HashMap<>(base);CloudManagedConfig.overlay(directory,reloaded::put);ProviderConfiguration again=new ProviderConfiguration(reloaded::get,keys,directory.toRealPath());ConnectionGuard.applyProviders(again);
        assertEquals(first.keys,again.keys);assertEquals(Arrays.asList("ip-api","zowi"),again.settings.vpnFailover.order);
        assertFalse(base.containsKey("provider.vpn-failover.order"));assertEquals(new Gson().toJsonTree(Arrays.asList("ip-api","zowi")),CloudManagedConfig.snapshot(reloaded::get).get("provider.vpn-failover.order"));
    }
    @Test void emptyManagedOrderAndDisabledFailoverRetainDeclaredConsensus() throws Exception {
        Map<String,Object> values=new HashMap<>();values.put("provider.geo.service","Disabled");values.put("provider.vpn.proxycheck.enabled",true);values.put("provider.vpn.zowi.enabled",true);values.put("required-positive-flags",2);
        values.putAll(CloudManagedConfig.parse(json("{\"provider.vpn-failover.order\":[],\"provider.vpn-failover.enabled\":false}")));
        ProviderConfiguration draft=new ProviderConfiguration(values::get,Arrays.asList("proxycheck","zowi"));assertFalse(draft.failover);assertEquals(2,draft.threshold);assertEquals(Arrays.asList("proxycheck","zowi"),draft.keys);
    }
    @Test void optionalIpQualityScoreSecretUsesExistingRedactionAndKeepSemantics() {
        String path="provider.vpn.ipqualityscore.api-key";CloudManagedConfig first=CloudManagedConfig.next(CloudManagedConfig.EMPTY,1,false,json("{\"provider.vpn.ipqualityscore.enabled\":true,\"provider.vpn.ipqualityscore.api-key\":\"private-fixture-1234\"}"),null);
        assertEquals("private-fixture-1234",CloudManagedConfig.next(first,2,false,new JsonObject(),JsonParser.parseString("[\""+path+"\"]").getAsJsonArray()).values.get(path));
        JsonObject snap=CloudManagedConfig.snapshot(first.values::get);assertTrue(snap.getAsJsonObject(path).get("set").getAsBoolean());assertEquals("1234",snap.getAsJsonObject(path).get("hint").getAsString());assertFalse(snap.toString().contains("private-fixture"));
    }
    @Test void managedOrderRebuildInvalidatesOldCachedDecisionInPlace() throws Exception {
        AtomicInteger clean=new AtomicInteger(),block=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{boolean deny=exchange.getRequestURI().getPath().contains("block");(deny?block:clean).incrementAndGet();byte[] data=("{\"vpn\":"+deny+"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();});server.start();
        try {
            Map<String,Object> base=new HashMap<>();base.put("provider.geo.service","Disabled");base.put("provider.cache.type","Memory");base.put("provider.vpn-failover.order",Arrays.asList("clean","block"));
            for(String id:new String[]{"clean","block"}) {String p="provider.vpn."+id+".";base.put(p+"enabled",true);base.put(p+"request-url","http://127.0.0.1:"+server.getAddress().getPort()+"/"+id+"/%IP%");base.put(p+"response-format.is-vpn-field.field-name","vpn");}
            ConnectionGuard.setCacheProvider(new MemoryCacheProvider());ProviderConfiguration first=new ProviderConfiguration(base::get,Arrays.asList("clean","block"));ConnectionGuard.applyProviders(first);
            assertEquals(ProviderVote.Status.NEGATIVE,ConnectionGuard.getVpnResult("192.0.2.9").get(3,TimeUnit.SECONDS).getStatus());assertTrue(ConnectionGuard.getVpnResult("192.0.2.9").get().isFromCache());idle();
            CloudManagedConfig.write(directory,CloudManagedConfig.next(CloudManagedConfig.EMPTY,1,false,json("{\"provider.vpn-failover.order\":[\"block\"]}"),null));Map<String,Object> changed=new HashMap<>(base);CloudManagedConfig.overlay(directory,changed::put);
            ProviderConfiguration next=new ProviderConfiguration(changed::get,Arrays.asList("clean","block"));ConnectionGuard.applyProviders(next);assertNotEquals(first.cacheNamespace,next.cacheNamespace);
            com.github.gerolndnr.connectionguard.core.vpn.VpnResult verdict=ConnectionGuard.getVpnResult("192.0.2.9").get(3,TimeUnit.SECONDS);assertTrue(verdict.isVpn());assertFalse(verdict.isFromCache());assertEquals(1,clean.get());assertEquals(1,block.get());assertEquals(Arrays.asList("clean","block"),base.get("provider.vpn-failover.order"));
        } finally {idle();server.stop(0);}
    }
}
