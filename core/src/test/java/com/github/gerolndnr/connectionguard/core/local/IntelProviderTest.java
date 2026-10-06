package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.cache.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Signed synthetic documentation networks; all HTTP remains on an owned loopback server. */
class IntelProviderTest {
    @TempDir Path directory;
    private HttpServer server;
    private KeyPair signer;
    private IntelDataStore store;
    private final Map<String,byte[]> responses=new ConcurrentHashMap<>();
    private final List<String> requests=new CopyOnWriteArrayList<>();
    private final OkHttpClient client=new OkHttpClient.Builder().callTimeout(2,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private long now;
    private JsonObject manifest;
    @BeforeEach void setup()throws Exception{
        directory=directory.toRealPath();now=System.currentTimeMillis();KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));signer=generator.generateKeyPair();
        store=new IntelDataStore(directory,settings("ALLOW"),signer.getPublic());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestURI().toString());String path=exchange.getRequestURI().getPath();byte[] bytes=responses.get(path);
            if(bytes==null){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();generation(now-1000);
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    private IntelSettings settings(String relay){Map<String,Object> v=new HashMap<>();v.put("provider.local.connectionguard-intel.enabled",true);v.put("provider.local.connectionguard-intel.relay",relay);return new IntelSettings(v::get);}
    private void generation(long asOf)throws Exception{
        manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("evaluation_only",false);manifest.addProperty("as_of",Instant.ofEpochMilli(asOf).toString());JsonObject lists=new JsonObject();
        Map<String,String> networks=new LinkedHashMap<>();networks.put("vpn","192.0.2.0/28\n2001:db8:1::/48\n");networks.put("tor","192.0.2.32/28\n");networks.put("relay","192.0.2.64/28\n192.0.2.0/28\n");networks.put("hosting","192.0.2.96/28\n");
        for(Map.Entry<String,String> e:networks.entrySet()){
            byte[] bytes=e.getValue().getBytes(StandardCharsets.UTF_8);responses.put("/"+e.getKey()+".txt",bytes);JsonObject file=new JsonObject();file.addProperty("file",e.getKey()+".txt");file.addProperty("bytes",bytes.length);file.addProperty("networks",e.getValue().split("\n").length);file.addProperty("sha256",LocalSource.hash(bytes));lists.add(e.getKey().toUpperCase(Locale.ROOT),file);
        }manifest.add("lists",lists);sign();
    }
    private void sign()throws Exception{byte[] bytes=manifest.toString().getBytes(StandardCharsets.UTF_8);responses.put("/manifest.json",bytes);Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(signer.getPrivate());signature.update(bytes);responses.put("/manifest.json.sig",Base64.getEncoder().encode(signature.sign()));}
    private IntelSnapshot update()throws Exception{return store.updateFixture(HttpUrl.get("http://127.0.0.1:"+server.getAddress().getPort()+"/"),client);}
    @Test void completeBundleVerifiedBeforeAtomicPointerAndSurvivesRestart()throws Exception{
        IntelSnapshot published=update();assertEquals(FailureReason.NONE,published.readiness(now));assertTrue(published.vpn("192.0.2.1",now).isVpn());assertTrue(published.vpn("2001:db8:1::1",now).isVpn());assertEquals(published.generation,store.load(now).generation);
        assertEquals(Arrays.asList("/manifest.json","/manifest.json.sig","/vpn.txt","/tor.txt","/relay.txt","/hosting.txt"),requests);
        assertEquals(published.generation,new IntelDataStore(directory,settings("VPN"),signer.getPublic()).load(now).generation);
    }
    @ParameterizedTest @ValueSource(strings={"signature","hash","size","count","oversized-manifest","oversized-signature","bad-base64","file","evaluation","future","missing-file","missing-type","old-generation"})
    void badUpdatesPreserveWholeLastGoodGeneration(String fault)throws Exception{
        IntelSnapshot original=update();Path pointer=directory.resolve("local-data/connectionguard-intel/current.json");byte[] before=Files.readAllBytes(pointer);
        generation(now+1000);
        switch(fault){
            case "hash":manifest.getAsJsonObject("lists").getAsJsonObject("VPN").addProperty("sha256",String.join("",Collections.nCopies(64,"0")));sign();break;
            case "size":manifest.getAsJsonObject("lists").getAsJsonObject("TOR").addProperty("bytes",1);sign();break;
            case "count":manifest.getAsJsonObject("lists").getAsJsonObject("HOSTING").addProperty("networks",2);sign();break;
            case "oversized-manifest":responses.put("/manifest.json",new byte[65537]);break;
            case "oversized-signature":responses.put("/manifest.json.sig",new byte[257]);break;
            case "bad-base64":responses.put("/manifest.json.sig","***".getBytes(StandardCharsets.US_ASCII));break;
            case "signature":byte[] signature=responses.get("/manifest.json.sig");signature[3]^=1;break;
            case "file":manifest.getAsJsonObject("lists").getAsJsonObject("VPN").addProperty("file","../other");sign();break;
            case "evaluation":manifest.addProperty("evaluation_only",true);sign();break;
            case "future":generation(now+600000);break;
            case "missing-file":responses.remove("/hosting.txt");break;
            case "missing-type":manifest.getAsJsonObject("lists").remove("TOR");sign();break;
            case "old-generation":generation(now-2000);break;
        }
        assertThrows(java.io.IOException.class,()->update());assertArrayEquals(before,Files.readAllBytes(pointer));
        IntelSnapshot retained=store.load(now);assertEquals(original.generation,retained.generation);assertTrue(retained.vpn("192.0.2.1",now).isVpn());
    }
    @Test void forgedManifestNeverFetchesAnyLists()throws Exception{responses.get("/manifest.json")[0]^=1;assertThrows(java.io.IOException.class,()->update());assertEquals(Arrays.asList("/manifest.json","/manifest.json.sig"),requests);}
    @Test void staleSnapshotHasNoUsableClassification()throws Exception{IntelSnapshot good=update();VpnResult stale=good.vpn("192.0.2.1",good.asOf+TimeUnit.HOURS.toMillis(72));assertEquals(ProviderVote.Status.UNKNOWN,stale.getStatus());assertEquals(FailureReason.STALE_DATA,stale.getSourceReason());assertTrue(stale.getDetails().getClassifications().isEmpty());}
    @Test void unlistedIsUnknownAndHostingIsOnlyReview()throws Exception{IntelSnapshot good=update();assertEquals(ProviderVote.Status.UNKNOWN,good.vpn("203.0.113.1",now).getStatus());VpnResult hosting=good.vpn("192.0.2.97",now);assertEquals(ProviderVote.Status.UNKNOWN,hosting.getStatus());assertEquals(Boolean.TRUE,hosting.getDetails().get(DetectionDetails.Type.HOSTING));}
    @Test void relayAllowIsNegativeAndNeverFabricatesVpnType()throws Exception{VpnResult relay=update().vpn("192.0.2.65",now);assertEquals(ProviderVote.Status.NEGATIVE,relay.getStatus());assertEquals(Collections.singletonMap(DetectionDetails.Type.RELAY,true),relay.getDetails().getClassifications());assertFalse(relay.isVpn());}
    @Test void relayVpnIsPositiveAndStrongerVpnWinsOverlap()throws Exception{IntelSnapshot allowed=update();assertTrue(allowed.vpn("192.0.2.1",now).isVpn());IntelSnapshot selected=new IntelDataStore(directory,settings("VPN"),signer.getPublic()).load(now);assertTrue(selected.vpn("192.0.2.65",now).isVpn());assertTrue(selected.vpn("192.0.2.33",now).isVpn());}
    @Test void noPlayerAddressQueryOrHeaderIsNeededForBundle()throws Exception{IntelSnapshot s=update();int requestsBefore=requests.size();for(String ip:Arrays.asList("192.0.2.1","192.0.2.65","203.0.113.42"))s.vpn(ip,now);assertEquals(requestsBefore,requests.size());assertTrue(requests.stream().noneMatch(path->path.contains("?")||path.contains("192.0.2")||path.contains("203.0.113")));}
    @Test void wrongTrustKeyCannotLoadPreviousDiskGeneration()throws Exception{update();KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));assertThrows(java.io.IOException.class,()->new IntelDataStore(directory,settings("ALLOW"),generator.generateKeyPair().getPublic()).load(now));}
    @Test void cacheRetainsTypesTimeAndHonorsExpiry()throws Exception{IntelSnapshot good=update();VpnResult v=good.vpn("192.0.2.1",now);v.setCachedOn(System.currentTimeMillis());VpnResult cached=CacheCodec.vpn(CacheCodec.encode(v),"192.0.2.1",60000).get();assertEquals(good.asOf,cached.getDetails().getDataAsOf());assertEquals(v.getDetails().getClassifications(),cached.getDetails().getClassifications());v.setValidUntil(now-1);assertFalse(CacheCodec.vpn(CacheCodec.encode(v),"192.0.2.1",60000).isPresent());}
    @Test void pinnedProductionKeyHasExpectedFingerprintAndCurve()throws Exception{PublicKey key=IntelDataStore.pinnedKey();assertEquals(91,key.getEncoded().length);assertEquals("EC",key.getAlgorithm());assertEquals("5758ebe5edf68bd6239d45fce6dec47b852e14dbdfe4010420e4263c7cdb135f",LocalSource.hash(key.getEncoded()));}
    @Test void actualFailoverUsesIntelBeforeProxycheckAndMissOrStaleAdvances()throws Exception{
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.setLogger(null);
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.setCacheProvider(new NoCacheProvider());
        Map<String,Object> v=new HashMap<>();v.put("provider.local.connectionguard-intel.enabled",true);v.put("provider.geo.service","Disabled");v.put("provider.vpn.proxycheck.enabled",true);v.put("provider.max-external-attempts",1);
        com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration draft=new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(v::get,Arrays.asList("proxycheck"));
        assertEquals(Arrays.asList("connectionguard-intel","proxycheck"),draft.keys);
        IntelSnapshot fresh=update();draft.providers.set(0,new IntelVpnProvider(fresh));
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        draft.providers.set(1,new ProxyCheckVpnProvider(""){
            @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip,long remaining,Runnable attempted){
                calls.incrementAndGet();attempted.run();JsonObject json=new JsonObject();json.addProperty("status","ok");JsonObject answer=new JsonObject();answer.addProperty("proxy","yes");answer.addProperty("type","VPN");json.add(ip,answer);return CompletableFuture.completedFuture(parse(ip,json));
            }
        });
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.applyProviders(draft);
        VpnResult listed=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getVpnResult("192.0.2.1").get(2,TimeUnit.SECONDS);assertTrue(listed.isVpn());assertEquals(0,calls.get());assertEquals("connectionguard-intel",listed.getVotes().get(0).getProvider());
        VpnResult relay=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getVpnResult("192.0.2.65").get(2,TimeUnit.SECONDS);assertEquals(ProviderVote.Status.NEGATIVE,relay.getStatus());assertEquals(0,calls.get());
        VpnResult miss=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getVpnResult("203.0.113.1").get(2,TimeUnit.SECONDS);assertTrue(miss.isVpn());assertEquals(1,calls.get());assertEquals(FailureReason.NO_EVIDENCE,miss.getVotes().get(0).getReason());
        assertEquals(fresh.asOf,listed.getVotes().get(0).getDetails().getDataAsOf());
        generation(now-TimeUnit.HOURS.toMillis(72)-1000);Map<LocalSource.Kind,byte[]> oldFiles=new EnumMap<>(LocalSource.Kind.class);
        for(LocalSource.Kind kind:IntelSnapshot.kinds())oldFiles.put(kind,responses.get("/"+kind.name().toLowerCase(Locale.ROOT)+".txt"));
        store.verify(responses.get("/manifest.json"),responses.get("/manifest.json.sig"));
        IntelSnapshot stale=IntelSnapshot.parse(settings("ALLOW"),responses.get("/manifest.json"),oldFiles,now,now);draft.providers.set(0,new IntelVpnProvider(stale));
        VpnResult staleMiss=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getVpnResult("192.0.2.2").get(2,TimeUnit.SECONDS);assertTrue(staleMiss.isVpn());assertEquals(2,calls.get());assertEquals(FailureReason.STALE_DATA,staleMiss.getVotes().get(0).getReason());
        long timeout=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<timeout)Thread.sleep(2);
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.applyProviders(new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(k->null,Collections.emptyList()));
    }

    @Test void finalDecisionExpiryDropsTypesButRetainsKnownPublicationTime()throws Exception{
        IntelSnapshot good=update();VpnResult v=good.vpn("192.0.2.1",now);
        v.setVotes(Collections.singletonList(new ProviderVote("connectionguard-intel",ProviderVote.Status.POSITIVE,FailureReason.NONE,0,v.getDetails(),good.validUntil(),good.generation,true)));
        VpnResult expired=LookupFreshness.vpn(v,good.validUntil());assertEquals(ProviderVote.Status.UNKNOWN,expired.getStatus());
        assertTrue(expired.getVotes().get(0).getDetails().getClassifications().isEmpty());assertEquals(good.asOf,expired.getVotes().get(0).getDetails().getDataAsOf());
    }

    @Test void intelMembershipNeverWaitsBehindSaturatedHttpWorkers()throws Exception{
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.setLogger(null);
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.setCacheProvider(new NoCacheProvider());
        Map<String,Object> v=new HashMap<>();v.put("provider.local.connectionguard-intel.enabled",true);v.put("provider.geo.service","Disabled");v.put("lookup.workers",1);v.put("lookup.queue-capacity",1);
        com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration draft=new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(v::get,Collections.emptyList());
        draft.providers.set(0,new IntelVpnProvider(update()));
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.applyProviders(draft);
        LookupRuntime runtime=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        CompletableFuture<Void> active=runtime.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;});
        assertTrue(entered.await(1,TimeUnit.SECONDS));CompletableFuture<Void> queued=runtime.submit(()->null);
        try{
            for(int i=1;i<16;i++){
                CompletableFuture<VpnResult> lookup=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getVpnResult("192.0.2."+i);
                assertTrue(lookup.isDone(),"Immutable Intel membership must not allocate a transport job");assertTrue(lookup.get().isVpn());
            }
            assertEquals(1,runtime.getQueueSize());
        }finally{
            release.countDown();active.get(1,TimeUnit.SECONDS);queued.get(1,TimeUnit.SECONDS);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!runtime.isIdle()&&System.nanoTime()<until)Thread.sleep(2);
            com.github.gerolndnr.connectionguard.core.ConnectionGuard.applyProviders(new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(k->null,Collections.emptyList()));
        }
    }

}
