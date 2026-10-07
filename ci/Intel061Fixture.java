package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.*;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.libs.com.google.gson.*;
import com.github.gerolndnr.connectionguard.libs.okhttp3.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Compiled against ONLY the actual shaded JAR. Synthetic signed data and owned loopback APIs. */
public final class Intel061Fixture {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static BlackboxVpnProvider blackbox(IntelSnapshot intel,boolean confirm,HttpUrl url)throws Exception{
        java.lang.reflect.Constructor<BlackboxVpnProvider> constructor=BlackboxVpnProvider.class.getDeclaredConstructor(boolean.class,IntelSnapshot.class,HttpUrl.class);
        constructor.setAccessible(true);return constructor.newInstance(confirm,intel,url);
    }
    private static ZowiVpnProvider zowi(HttpUrl url)throws Exception{
        java.lang.reflect.Constructor<ZowiVpnProvider> constructor=ZowiVpnProvider.class.getDeclaredConstructor(HttpUrl.class);
        constructor.setAccessible(true);return constructor.newInstance(url);
    }
    private static void activate(IntelSnapshot snapshot,boolean confirm,HttpUrl base)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<until)Thread.sleep(2);
        check(ConnectionGuard.getLookupRuntime().isIdle(),"Previous fixture still active");
        Map<String,Object> values=new HashMap<>();values.put("provider.geo.service","Disabled");values.put("provider.local.connectionguard-intel.enabled",true);
        values.put("provider.vpn.blackbox.enabled",true);values.put("provider.vpn.zowi.enabled",true);values.put("provider.vpn-failover.order",Arrays.asList("blackbox","zowi"));values.put("provider.vpn.blackbox.require-confirmation",confirm);
        ProviderConfiguration draft=new ProviderConfiguration(values::get,Arrays.asList("blackbox","zowi"));
        draft.providers.set(0,new IntelVpnProvider(snapshot));draft.providers.set(1,blackbox(snapshot,confirm,base.newBuilder().addPathSegment("blackbox").build()));draft.providers.set(2,zowi(base.newBuilder().addPathSegment("zowi").build()));
        ConnectionGuard.applyProviders(draft);
    }
    public static void main(String[] args)throws Exception{
        Path directory=Paths.get(args[0]).toRealPath();long now=System.currentTimeMillis();
        Map<String,byte[]> responses=new ConcurrentHashMap<>();AtomicInteger blackboxCalls=new AtomicInteger(),zowiCalls=new AtomicInteger(),downloads=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();String key;
            if(path.startsWith("/blackbox/")){blackboxCalls.incrementAndGet();key="blackbox";}
            else if(path.startsWith("/zowi/")){zowiCalls.incrementAndGet();key="zowi";}
            else{downloads.incrementAndGet();key=path;}
            byte[] bytes=responses.get(key);if(bytes==null){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).build();
        try{
            HttpUrl base=HttpUrl.get("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));KeyPair signer=generator.generateKeyPair();
            IntelSettings settings=new IntelSettings(k->k.endsWith("enabled")?true:null);IntelDataStore store=new IntelDataStore(directory,settings,signer.getPublic());
            JsonObject manifest=new JsonObject(),lists=new JsonObject(),additional=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("evaluation_only",false);manifest.addProperty("as_of",Instant.ofEpochMilli(now-1000).toString());
            String[] names={"VPN","TOR","RELAY","HOSTING","PROXY"},networks={"192.0.2.0/28\n","192.0.2.32/28\n","192.0.2.64/28\n","203.0.113.0/28\n","192.0.2.64/28\n2001:db8:2::/48\n"};
            for(int i=0;i<names.length;i++){
                String file=names[i].toLowerCase(Locale.ROOT)+".txt";byte[] bytes=networks[i].getBytes(StandardCharsets.UTF_8);responses.put("/"+file,bytes);JsonObject metadata=new JsonObject();metadata.addProperty("file",file);metadata.addProperty("sha256",LocalSource.hash(bytes));metadata.addProperty("bytes",bytes.length);metadata.addProperty("networks",networks[i].split("\n").length);
                (i==4?additional:lists).add(names[i],metadata);
            }
            manifest.add("lists",lists);manifest.add("additional_lists",additional);sign(manifest,signer,responses);
            IntelSnapshot snapshot=store.updateFixture(base,client);check(snapshot.proxyState==IntelSnapshot.ProxyState.LOADED,"Optional proxy load");check(store.load(now).recordCount(LocalSource.Kind.PROXY)==2,"Proxy survives restart");
            responses.put("blackbox","Y".getBytes(StandardCharsets.US_ASCII));String clean="{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"hosting\":false}}";responses.put("zowi",clean.getBytes(StandardCharsets.UTF_8));
            ConnectionGuard.setLogger(null);ConnectionGuard.setCacheProvider(new NoCacheProvider());activate(snapshot,true,base);
            VpnResult local=ConnectionGuard.getVpnResult("192.0.2.65").get(3,TimeUnit.SECONDS);check(local.isVpn(),"Proxy beats RELAY ALLOW");check(Boolean.TRUE.equals(local.getVotes().get(0).getDetails().get(DetectionDetails.Type.PROXY)),"Proxy source type retained");check(blackboxCalls.get()==0&&zowiCalls.get()==0,"Local proxy performs no HTTP");
            check(ConnectionGuard.getVpnResult("2001:db8:2::1").get(3,TimeUnit.SECONDS).isVpn(),"IPv6 proxy positive");
            VpnResult consumer=ConnectionGuard.getVpnResult("198.51.100.2").get(3,TimeUnit.SECONDS);check(consumer.getStatus()==ProviderVote.Status.NEGATIVE,"Unconfirmed Y then clean Zowi allows");check(consumer.getVotes().get(1).getReason()==FailureReason.NO_EVIDENCE,"Unconfirmed source code");check(blackboxCalls.get()==1&&zowiCalls.get()==1,"One normal request per reached service");
            responses.put("zowi",clean.replace("detected\":false","detected\":true").getBytes(StandardCharsets.UTF_8));check(ConnectionGuard.getVpnResult("198.51.100.3").get(3,TimeUnit.SECONDS).isVpn(),"Next normal positive refuses");
            int zowiBefore=zowiCalls.get();VpnResult hosted=ConnectionGuard.getVpnResult("203.0.113.2").get(3,TimeUnit.SECONDS);check(hosted.isVpn(),"Fresh HOSTING confirms Y");check(hosted.getVotes().get(1).getValidUntil()==snapshot.validUntil(),"Confirmation expiry bound");check(zowiCalls.get()==zowiBefore,"Confirmed Y stops chain");
            activate(snapshot,false,base);check(ConnectionGuard.getVpnResult("198.51.100.4").get(3,TimeUnit.SECONDS).isVpn(),"Explicit legacy Y blocks");check(zowiCalls.get()==zowiBefore,"Legacy opt-out stops chain");
            activate(snapshot,true,base);responses.put("blackbox","N".getBytes(StandardCharsets.US_ASCII));check(!ConnectionGuard.getVpnResult("198.51.100.5").get(3,TimeUnit.SECONDS).isVpn(),"N stays negative");check(zowiCalls.get()==zowiBefore,"N stops chain");
            // Reject only an optional file; still atomically publish and reload verified base lists.
            manifest.addProperty("as_of",Instant.ofEpochMilli(now+1000).toString());additional.getAsJsonObject("PROXY").addProperty("sha256",String.join("",Collections.nCopies(64,"0")));sign(manifest,signer,responses);
            IntelSnapshot skipped=store.updateFixture(base,client);check(skipped.proxyState==IntelSnapshot.ProxyState.SKIPPED,"Bad proxy skipped");check(skipped.vpn("192.0.2.1",now).isVpn(),"Base VPN retained");check(store.load(now).proxyState==IntelSnapshot.ProxyState.SKIPPED,"Skip persists on reload");
            JsonObject result=new JsonObject();result.addProperty("status","passed");result.addProperty("cases",10);result.addProperty("blackbox_requests",blackboxCalls.get());result.addProperty("zowi_requests",zowiCalls.get());result.addProperty("intel_file_requests",downloads.get());result.addProperty("synthetic_loopback_only",true);result.addProperty("scope","actual shaded-JAR core and HTTP fixtures; not a Minecraft login or competitive detection test");Files.write(directory.resolve("result.json"),result.toString().getBytes(StandardCharsets.UTF_8));
        }finally{server.stop(0);client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
        System.exit(0);
    }
    private static void sign(JsonObject manifest,KeyPair signer,Map<String,byte[]> responses)throws Exception{
        byte[] bytes=manifest.toString().getBytes(StandardCharsets.UTF_8);Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(signer.getPrivate());signature.update(bytes);responses.put("/manifest.json",bytes);responses.put("/manifest.json.sig",Base64.getEncoder().encode(signature.sign()));
    }
}
