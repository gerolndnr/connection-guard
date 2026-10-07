package com.github.gerolndnr.connectionguard.core.cache;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import java.util.concurrent.*;

/** Serializes JDBC/Jedis access, bounds pending cache operations, redacts backend exception messages. */
abstract class SerialCacheProvider implements CacheProvider {
    @FunctionalInterface interface Operation<T> { T run() throws Exception; }
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "ConnectionGuard-cache"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean stopping;
    private CompletableFuture<Boolean> stopResult;
    protected volatile String namespace = "cg:v2:default";
    @Override public void setNamespace(String namespace) {
        if (!namespace.matches("[a-zA-Z0-9:_-]{1,100}")) throw new IllegalArgumentException("Invalid cache namespace.");
        this.namespace = "cg:v2:" + namespace;
    }
    protected <T> CompletableFuture<T> submit(Operation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if(stopping){result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR));return result;}
        try { executor.execute(() -> {
            try { result.complete(operation.run()); }
            catch (Exception failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.CACHE); result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR)); }
        }); } catch (RejectedExecutionException full) { result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR)); }
        return result;
    }
    /** Persistence is best effort under queue pressure; this is not a backend failure. */
    protected CompletableFuture<Void> submitWrite(Operation<Void> operation) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        if(stopping){result.complete(null);return result;}
        try {executor.execute(()->{
            try{result.complete(operation.run());}
            catch(Exception failure){com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure,com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.CACHE);result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR));}
        });}catch(RejectedExecutionException pressure){result.complete(null);}
        return result;
    }
    protected synchronized CompletableFuture<Boolean> stop(Operation<Boolean> operation) {
        if(stopResult!=null)return stopResult;
        stopping=true;executor.shutdown();
        CompletableFuture<Boolean> result=new CompletableFuture<>();stopResult=result;
        // Shutdown is not submitted to the bounded work queue: pressure cannot drop resource cleanup.
        Thread cleanup=new Thread(()->{
            try{
                boolean timely=executor.awaitTermination(4,TimeUnit.SECONDS);
                if(!timely){result.complete(false);while(!executor.awaitTermination(4,TimeUnit.SECONDS)){ /* bounded socket calls drain */ }}
                boolean ok=operation.run();result.complete(ok);
            }catch(Exception failure){result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR));}
        },"ConnectionGuard-cache-stop");cleanup.setDaemon(true);cleanup.start();return result;
    }
    protected String key(String type, String ip) { return namespace + ":" + type + ":" + ip; }
}
