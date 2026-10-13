package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.libs.okhttp3.HttpUrl;
import com.github.gerolndnr.connectionguard.libs.org.yaml.snakeyaml.Yaml;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual shaded providers, embedded YAML and owned HTTP. No public provider requests. */
public final class ProviderOrderJarCases {
    static final Map<String,AtomicInteger> calls=new ConcurrentHashMap<>();
    static final Map<String,String> bodies=new ConcurrentHashMap<>();
    static volatile boolean timeout;
    static int port;
    static final String CLEAN="{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"hosting\":false}}";
    static Object value(Map<String,Object> map,String path){Object result=map;for(String key:path.split("\\."))result=result instanceof Map?((Map<?,?>)result).get(key):null;return result;}
    @SuppressWarnings("unchecked") static Map<String,Object> values(){return new Yaml().load(ProviderOrderJarCases.class.getResourceAsStream("/config.yml"));}
    static void require(boolean success){if(!success)throw new AssertionError("Owned provider-order contract failed");}
    static void idle() throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!ConnectionGuard.getLookupRuntime().isIdle()&&System.nanoTime()<end)Thread.sleep(2);require(ConnectionGuard.getLookupRuntime().isIdle());}
    @SuppressWarnings("unchecked") static ProviderConfiguration activate(Map<String,Object> yaml)throws Exception {
        idle(); ProviderConfiguration draft=new ProviderConfiguration(path->value(yaml,path),new ArrayList<>(((Map<String,Object>)value(yaml,"provider.vpn")).keySet()));
        for(int i=0;i<draft.providers.size();i++){
            String id=draft.keys.get(i);HttpUrl endpoint=HttpUrl.get("http://127.0.0.1:"+port+"/"+id);
            if(id.equals("zowi"))draft.providers.set(i,new ZowiVpnProvider(endpoint));
            if(id.equals("blackbox"))draft.providers.set(i,new BlackboxVpnProvider(true,draft.intelSnapshot,endpoint));
            if(id.equals("ipquery"))draft.providers.set(i,new IpQueryVpnProvider(HttpUrl.get(endpoint.toString()+"/")));
        }
        ConnectionGuard.applyProviders(draft);return draft;
    }
    static VpnResult query(String ip)throws Exception{return ConnectionGuard.getVpnResult(ip).get(8,TimeUnit.SECONDS);}
    static int count(String id){return calls.containsKey(id)?calls.get(id).get():0;}
    static void passed(String name){System.out.println("PROVIDER_ORDER_JAR_CASE "+name);}
    public static void main(String[] args)throws Exception {
        ConnectionGuard.setLogger(null);ConnectionGuard.setCacheProvider(new NoCacheProvider());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);port=server.getAddress().getPort();
        ExecutorService workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/",exchange->{String id=exchange.getRequestURI().getPath().split("/")[1];calls.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
            try {if(id.equals("zowi")&&timeout)Thread.sleep(2000);byte[] bytes=bodies.getOrDefault(id,"invalid").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(InterruptedException stopped){Thread.currentThread().interrupt();}catch(java.io.IOException cancelled){}finally{exchange.close();}});
        server.start();
        try {
            bodies.put("zowi",CLEAN);bodies.put("blackbox","Y");bodies.put("ipquery","{\"risk\":{\"is_vpn\":true,\"is_proxy\":false,\"is_tor\":false}}");
            Map<String,Object> yaml=values();ProviderConfiguration draft=activate(yaml);
            require(draft.keys.equals(Arrays.asList("connectionguard-intel","zowi","blackbox","ipquery")));
            require(Boolean.FALSE.equals(value(yaml,"provider.vpn.proxycheck.enabled"))&&Boolean.FALSE.equals(value(yaml,"provider.vpn.ipcheck.enabled"))&&Boolean.FALSE.equals(value(yaml,"provider.vpn.ip-api.enabled")));
            require(draft.geo==null&&draft.externalAttempts==16&&draft.settings.lookup.deadlineMillis==5000&&draft.settings.lookup.httpTimeoutMillis==1500);
            passed("embedded_template_new_order_and_limits");
            long started=System.nanoTime();require(query("192.0.2.1").getStatus()==ProviderVote.Status.NEGATIVE);
            require(count("zowi")==1&&count("blackbox")==0&&count("ipquery")==0);
            System.out.println("OWNED_SINGLE_LOOKUP_MS "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            passed("zowi_clean_stops_without_other_recipients");
            timeout=true;VpnResult failed=query("192.0.2.2");timeout=false;
            require(failed.isVpn());require(failed.getVotes().stream().anyMatch(v->v.getProvider().equals("zowi")&&v.getReason()==FailureReason.TIMEOUT));
            require(failed.getVotes().stream().anyMatch(v->v.getProvider().equals("blackbox")&&v.getReason()==FailureReason.NO_EVIDENCE));
            require(count("blackbox")==1&&count("ipquery")==1);
            passed("timeout_unconfirmed_blackbox_falls_to_ipquery");
            // Explicitly reset only test-source failures; do not refill either quota layer.
            ConnectionGuard.providerHealth().get("vpn.zowi").resetFailures();
            for(int i=3;i<=60;i++)require(query("192.0.2."+i).getStatus()==ProviderVote.Status.NEGATIVE);
            VpnResult exhausted=query("192.0.2.61");require(exhausted.isVpn());
            require(exhausted.getVotes().stream().anyMatch(v->v.getProvider().equals("zowi")&&v.getReason()==FailureReason.BUDGET_EXHAUSTED));
            require(count("zowi")==60&&count("blackbox")==2&&count("ipquery")==2);
            passed("sixty_minute_cap_preserved_and_ordinary_fallback_used");
            Map<String,Object> existing=new HashMap<>();existing.put("provider.geo.service","Disabled");existing.put("provider.vpn.proxycheck.enabled",true);
            existing.put("provider.vpn.proxycheck.api-key","owned-fixture-key");existing.put("provider.vpn.ipquery.enabled",true);
            existing.put("provider.vpn-failover.order",Arrays.asList("proxycheck","ipquery"));Map<String,Object> before=new HashMap<>(existing);
            ProviderConfiguration old=new ProviderConfiguration(existing::get,Arrays.asList("proxycheck","ipquery"));
            require(old.keys.equals(Arrays.asList("proxycheck","ipquery"))&&existing.equals(before));
            passed("explicit_existing_selection_order_and_key_unchanged");
            @SuppressWarnings("unchecked") Map<String,Object> proxy=(Map<String,Object>)value(yaml,"provider.vpn.proxycheck");proxy.put("enabled",true);proxy.put("api-key","owned-fixture-key");
            ProviderConfiguration opted=new ProviderConfiguration(path->value(yaml,path),new ArrayList<>(Arrays.asList("proxycheck","blackbox","ipcheck","zowi","ipquery","ip-api")));
            require(opted.keys.equals(Arrays.asList("connectionguard-intel","zowi","blackbox","ipquery","proxycheck")));
            passed("proxycheck_opt_in_is_a_late_fallback");
        } finally {timeout=false;server.stop(0);workers.shutdownNow();require(workers.awaitTermination(4,TimeUnit.SECONDS));idle();ConnectionGuard.shutdown();}
    }
}
