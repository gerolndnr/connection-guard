package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** WAL persistence: independent read lane, reusable statements, bounded coalesced write batches. */
public final class SQLiteCacheProvider implements CacheProvider {
    private final String location;
    private volatile String namespace="cg:v2:default";
    private final ScheduledThreadPoolExecutor writer=new ScheduledThreadPoolExecutor(1,task->daemon(task,"ConnectionGuard-cache-write"));
    private final ThreadPoolExecutor reader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),task->daemon(task,"ConnectionGuard-cache-read"),new ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<String,Write> pending=new LinkedHashMap<>();
    private Connection writes,reads;
    private PreparedStatement select,insert;
    private CompletableFuture<Boolean> readiness, stopped;
    private volatile boolean closed;
    private long dropped;
    private static Thread daemon(Runnable task,String name){Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;}
    private static final class Write {
        final String key,payload;final long expiry;final CompletableFuture<Void> done=new CompletableFuture<>();
        Write(String key,String payload,long expiry){this.key=key;this.payload=payload;this.expiry=expiry;}
    }
    public SQLiteCacheProvider(String location){this.location=location;writer.setRemoveOnCancelPolicy(true);}
    @Override public void setNamespace(String value){if(value==null || !value.matches("[a-zA-Z0-9:_-]{1,100}"))throw new IllegalArgumentException("Invalid cache namespace.");namespace="cg:v2:"+value;}
    private String key(String type,String ip){return namespace+":"+type+":"+ip;}
    @Override public synchronized CompletableFuture<Boolean> setup(){
        if(closed)return CompletableFuture.completedFuture(false);
        if(readiness!=null)return readiness;
        readiness=new CompletableFuture<>();
        writer.execute(()->{
            try {
                Class.forName("org.sqlite.JDBC");writes=DriverManager.getConnection("jdbc:sqlite:"+location);
                try(Statement statement=writes.createStatement()) {
                    statement.execute("PRAGMA busy_timeout=1000");statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA synchronous=NORMAL");
                    statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_cache_v2 (cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, expires INTEGER NOT NULL)");
                    statement.execute("CREATE INDEX IF NOT EXISTS cg_v2_expiry ON connectionguard_cache_v2(expires)");
                }
                insert=writes.prepareStatement("INSERT OR REPLACE INTO connectionguard_cache_v2 VALUES (?, ?, ?)");
                reads=DriverManager.getConnection("jdbc:sqlite:"+location);
                try(Statement statement=reads.createStatement()){statement.execute("PRAGMA busy_timeout=1000");statement.execute("PRAGMA query_only=ON");}
                select=reads.prepareStatement("SELECT payload FROM connectionguard_cache_v2 WHERE cache_key=? AND expires>?");
                writer.scheduleWithFixedDelay(this::flush,1000,1000,TimeUnit.MILLISECONDS);
                writer.scheduleWithFixedDelay(this::expire,60,60,TimeUnit.SECONDS);
                readiness.complete(true);
            }catch(Exception failure){readiness.completeExceptionally(redacted(failure));}
        });return readiness;
    }
    private LookupException redacted(Exception failure){com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure,com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.CACHE);return new LookupException(FailureReason.CACHE_ERROR);}
    private CompletableFuture<Optional<String>> read(String key){
        CompletableFuture<Optional<String>> result=new CompletableFuture<>();
        if(closed || readiness==null || !readiness.isDone() || readiness.isCompletedExceptionally()){result.complete(Optional.empty());return result;}
        try {reader.execute(()->{
            try {
                // Direct backend users see an accepted pending answer; the memory-front wrapper normally answers it itself.
                Write staged;synchronized(this){staged=pending.get(key);}
                if(staged!=null){result.complete(staged.expiry>System.currentTimeMillis()?Optional.of(staged.payload):Optional.empty());return;}
                select.setString(1,key);select.setLong(2,System.currentTimeMillis());
                try(ResultSet rows=select.executeQuery()){result.complete(rows.next()?Optional.of(rows.getString(1)):Optional.empty());}
            }catch(Exception failure){result.completeExceptionally(redacted(failure));}
        });}catch(RejectedExecutionException pressure){result.complete(Optional.empty());}
        return result;
    }
    @Override public CompletableFuture<Optional<VpnResult>> getVpnResult(String ip){long ttl=ConnectionGuard.getVpnCacheExpirationTime()*60000L;return read(key("vpn",ip)).thenApply(value->CacheCodec.vpn(value.orElse(null),ip,ttl));}
    @Override public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip){long ttl=ConnectionGuard.getGeoCacheExpirationTime()*60000L;return read(key("geo",ip)).thenApply(value->CacheCodec.geo(value.orElse(null),ip,ttl));}
    private synchronized CompletableFuture<Void> write(String key,String payload,long expiry){
        if(closed)return CompletableFuture.completedFuture(null);
        Write next=new Write(key,payload,expiry),previous=pending.put(key,next);
        if(previous!=null)previous.done.complete(null);
        if(pending.size()>10000){Write removed=pending.remove(pending.keySet().iterator().next());removed.done.complete(null);dropped++;}
        if(pending.size()==100)try{writer.execute(this::flush);}catch(RejectedExecutionException stopped){next.done.complete(null);}
        return next.done;
    }
    @Override public CompletableFuture<Void> addVpnResult(VpnResult value){value.setCachedOn(System.currentTimeMillis());return restoreVpnResult(value);}
    @Override public CompletableFuture<Void> restoreVpnResult(VpnResult value){return write(key("vpn",value.getIpAddress()),CacheCodec.encode(value),value.getCachedOn()+ConnectionGuard.getVpnCacheExpirationTime()*60000L);}
    @Override public CompletableFuture<Void> addGeoResult(GeoResult value){value.setCachedOn(System.currentTimeMillis());return restoreGeoResult(value);}
    @Override public CompletableFuture<Void> restoreGeoResult(GeoResult value){return write(key("geo",value.getIpAddress()),CacheCodec.encode(value),value.getCachedOn()+ConnectionGuard.getGeoCacheExpirationTime()*60000L);}
    private void flush(){
        List<Write> batch;
        synchronized(this){if(pending.isEmpty() || writes==null || insert==null)return;batch=new ArrayList<>(pending.values());pending.clear();}
        try {
            writes.setAutoCommit(false);
            for(Write value:batch){insert.setString(1,value.key);insert.setString(2,value.payload);insert.setLong(3,value.expiry);insert.addBatch();}
            insert.executeBatch();writes.commit();for(Write value:batch)value.done.complete(null);
        }catch(Exception failure){try{writes.rollback();}catch(SQLException ignored){}LookupException safe=redacted(failure);for(Write value:batch)value.done.completeExceptionally(safe);}
        finally{try{insert.clearBatch();writes.setAutoCommit(true);}catch(SQLException ignored){}}
    }
    private void expire(){try(PreparedStatement delete=writes.prepareStatement("DELETE FROM connectionguard_cache_v2 WHERE expires<?")){delete.setLong(1,System.currentTimeMillis());delete.executeUpdate();}catch(Exception failure){redacted(failure);}}
    private synchronized CompletableFuture<Boolean> remove(String exact,String prefix){
        pending.entrySet().removeIf(entry->{boolean match=exact!=null?entry.getKey().equals(exact):entry.getKey().startsWith(prefix);if(match)entry.getValue().done.complete(null);return match;});
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        if(closed){result.complete(false);return result;}
        try{writer.execute(()->{
            try(PreparedStatement delete=writes.prepareStatement(exact!=null?"DELETE FROM connectionguard_cache_v2 WHERE cache_key=?":"DELETE FROM connectionguard_cache_v2 WHERE substr(cache_key,1,?)=?")){
                if(exact!=null)delete.setString(1,exact);else{delete.setInt(1,prefix.length());delete.setString(2,prefix);}
                delete.executeUpdate();result.complete(true);
            }catch(Exception failure){result.completeExceptionally(redacted(failure));}
        });}catch(RejectedExecutionException stopped){result.complete(false);}return result;
    }
    @Override public CompletableFuture<Boolean> removeVpnResult(String ip){return remove(key("vpn",ip),null);}
    @Override public CompletableFuture<Boolean> removeGeoResult(String ip){return remove(key("geo",ip),null);}
    @Override public CompletableFuture<Boolean> removeAllVpnResults(){return remove(null,namespace+":vpn:");}
    @Override public CompletableFuture<Boolean> removeAllGeoResults(){return remove(null,namespace+":geo:");}
    @Override public synchronized CompletableFuture<Boolean> disband(){
        if(stopped!=null)return stopped;closed=true;
        CompletableFuture<Boolean> result=new CompletableFuture<>();stopped=result;
        writer.execute(()->{
            flush();reader.shutdown();
            try {
                if(!reader.awaitTermination(3,TimeUnit.SECONDS)){result.complete(false);return;}
                if(select!=null)select.close();if(reads!=null)reads.close();if(insert!=null)insert.close();if(writes!=null)writes.close();result.complete(true);
            }catch(Exception failure){result.completeExceptionally(redacted(failure));}
        });writer.shutdown();return result;
    }
    public synchronized String describe(){return "SQLite WAL batchMs=1000 pending="+pending.size()+" droppedWrites="+dropped;}
}
