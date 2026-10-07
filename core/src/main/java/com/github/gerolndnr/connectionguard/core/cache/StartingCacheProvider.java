package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Optional persistence boots off the event thread. Until ready, facts use bounded Memory. */
public final class StartingCacheProvider implements CacheProvider {
    private final Supplier<CacheProvider> factory;
    private final MemoryCacheProvider early=new MemoryCacheProvider();
    private final ExecutorService bootstrap=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"ConnectionGuard-cache-start");t.setDaemon(true);return t;});
    private final LinkedHashMap<String,String> writes=new LinkedHashMap<>();
    private final List<CompletableFuture<Boolean>> edits=new ArrayList<>();
    private final CompletableFuture<Boolean> stopped=new CompletableFuture<>();
    private CacheProvider ready;
    private String namespace="default",state="starting";
    private boolean started,closed,clearVpn,clearGeo;
    public StartingCacheProvider(Supplier<CacheProvider> factory){this.factory=Objects.requireNonNull(factory);}
    @Override public synchronized void setNamespace(String next){
        early.setNamespace(next);
        if(!namespace.equals(next)){
            namespace=next;writes.clear();clearVpn=clearGeo=false;
            for(CompletableFuture<Boolean> edit:edits)edit.complete(false);edits.clear();
        }
        if(ready!=null)ready.setNamespace(next);
    }
    @Override public synchronized CompletableFuture<Boolean> setup(){
        if(closed)return CompletableFuture.completedFuture(false);
        if(!started){started=true;bootstrap.execute(this::start);}
        return early.setup();
    }
    private void start(){
        CacheProvider backend=null;
        try{
            backend=factory.get();
            synchronized(this){backend.setNamespace(namespace);}
            if(!Boolean.TRUE.equals(backend.setup().get(30,TimeUnit.SECONDS)))throw new IllegalStateException();
            for(;;){
                String scope;Map<String,String> batch;List<CompletableFuture<Boolean>> acknowledgements;boolean vpn,geo;
                synchronized(this){
                    scope=namespace;batch=new LinkedHashMap<>(writes);writes.clear();
                    vpn=clearVpn;geo=clearGeo;clearVpn=clearGeo=false;
                    acknowledgements=new ArrayList<>(edits);edits.clear();
                }
                backend.setNamespace(scope);
                boolean invalidated=true;
                try{
                    if(vpn)invalidated=Boolean.TRUE.equals(backend.removeAllVpnResults().get(5,TimeUnit.SECONDS));
                    if(geo)invalidated=Boolean.TRUE.equals(backend.removeAllGeoResults().get(5,TimeUnit.SECONDS)) && invalidated;
                }catch(Exception failedEdit){invalidated=false;}
                for(CompletableFuture<Boolean> edit:acknowledgements)edit.complete(invalidated);
                if(!invalidated)throw new IllegalStateException();
                for(Map.Entry<String,String> entry:batch.entrySet()){
                    String ip=entry.getKey().substring(4);
                    if(entry.getKey().startsWith("vpn:"))CacheCodec.vpn(entry.getValue(),ip,ConnectionGuard.getVpnCacheExpirationTime()*60000L).ifPresent(backend::restoreVpnResult);
                    else CacheCodec.geo(entry.getValue(),ip,ConnectionGuard.getGeoCacheExpirationTime()*60000L).ifPresent(backend::restoreGeoResult);
                }
                synchronized(this){
                    if(!namespace.equals(scope) || !writes.isEmpty() || clearVpn || clearGeo)continue;
                    ready=backend;state="ready";early.disband();
                    if(!closed)return;
                }
                stopped.complete(Boolean.TRUE.equals(backend.disband().get(5,TimeUnit.SECONDS)));return;
            }
        }catch(Exception | LinkageError failure){
            synchronized(this){
                state="unavailable; bounded Memory fallback";
                for(CompletableFuture<Boolean> edit:edits)edit.complete(false);edits.clear();writes.clear();
                if(ConnectionGuard.getLogger()!=null)ConnectionGuard.getLogger().warning("Persistent cache unavailable: using bounded Memory; connection checks remain active (details redacted).");
            }
            if(backend!=null)try{backend.disband().get(5,TimeUnit.SECONDS);}catch(Exception ignored){}
            synchronized(this){if(closed){early.disband();stopped.complete(true);}}
        }finally{bootstrap.shutdown();}
    }
    @Override public synchronized CompletableFuture<Optional<VpnResult>> getVpnResult(String ip){return closed?CompletableFuture.completedFuture(Optional.empty()):ready==null?early.getVpnResult(ip):ready.getVpnResult(ip);}
    @Override public synchronized CompletableFuture<Optional<GeoResult>> getGeoResult(String ip){return closed?CompletableFuture.completedFuture(Optional.empty()):ready==null?early.getGeoResult(ip):ready.getGeoResult(ip);}
    private void stage(String key,String payload){
        if(!state.equals("starting"))return;
        writes.put(key,payload);
        if(writes.size()>10000)writes.remove(writes.keySet().iterator().next());
    }
    @Override public synchronized CompletableFuture<Void> addVpnResult(VpnResult value){
        if(closed)return CompletableFuture.completedFuture(null);
        if(ready!=null)return ready.addVpnResult(value);
        early.addVpnResult(value);stage("vpn:"+value.getIpAddress(),CacheCodec.encode(value));return CompletableFuture.completedFuture(null);
    }
    @Override public synchronized CompletableFuture<Void> addGeoResult(GeoResult value){
        if(closed)return CompletableFuture.completedFuture(null);
        if(ready!=null)return ready.addGeoResult(value);
        early.addGeoResult(value);stage("geo:"+value.getIpAddress(),CacheCodec.encode(value));return CompletableFuture.completedFuture(null);
    }
    private CompletableFuture<Boolean> edited(boolean vpn,String ip){
        String prefix=vpn?"vpn:":"geo:";
        if(ip==null)writes.keySet().removeIf(key->key.startsWith(prefix));else writes.remove(prefix+ip);
        if(!state.equals("starting"))return CompletableFuture.completedFuture(true);
        // Coalesce deletions into a scoped clear during bootstrap. Never resurrect old disk facts.
        if(vpn)clearVpn=true;else clearGeo=true;
        if(edits.size()>=10000)return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> result=new CompletableFuture<>();edits.add(result);return result;
    }
    @Override public synchronized CompletableFuture<Boolean> removeVpnResult(String ip){if(ready!=null)return ready.removeVpnResult(ip);early.removeVpnResult(ip);return edited(true,ip);}
    @Override public synchronized CompletableFuture<Boolean> removeGeoResult(String ip){if(ready!=null)return ready.removeGeoResult(ip);early.removeGeoResult(ip);return edited(false,ip);}
    @Override public synchronized CompletableFuture<Boolean> removeAllVpnResults(){if(ready!=null)return ready.removeAllVpnResults();early.removeAllVpnResults();return edited(true,null);}
    @Override public synchronized CompletableFuture<Boolean> removeAllGeoResults(){if(ready!=null)return ready.removeAllGeoResults();early.removeAllGeoResults();return edited(false,null);}
    @Override public synchronized CompletableFuture<Boolean> disband(){
        if(closed)return stopped;closed=true;
        if(ready!=null)ready.disband().whenComplete((ok,error)->{if(error==null)stopped.complete(ok);else stopped.completeExceptionally(error);});
        else if(!started || !state.equals("starting")){early.disband();bootstrap.shutdown();stopped.complete(true);}
        return stopped;
    }
    public synchronized String describe(){
        String detail=ready instanceof TieredCacheProvider?((TieredCacheProvider)ready).describe():ready instanceof ResilientRedisCacheProvider?((ResilientRedisCacheProvider)ready).describe():"Memory first (10000 entries)";
        return "Cache persistence="+state+"; "+detail;
    }
}
