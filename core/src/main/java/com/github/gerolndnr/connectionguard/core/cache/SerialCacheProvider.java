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
    protected volatile String namespace = "cg:v2:default";
    @Override public void setNamespace(String namespace) {
        if (!namespace.matches("[a-zA-Z0-9:_-]{1,100}")) throw new IllegalArgumentException("Invalid cache namespace.");
        this.namespace = "cg:v2:" + namespace;
    }
    protected <T> CompletableFuture<T> submit(Operation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try { executor.execute(() -> {
            try { result.complete(operation.run()); }
            catch (Exception failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.CACHE); result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR)); }
        }); } catch (RejectedExecutionException full) { result.completeExceptionally(new LookupException(FailureReason.CACHE_ERROR)); }
        return result;
    }
    protected CompletableFuture<Boolean> stop(Operation<Boolean> operation) {
        CompletableFuture<Boolean> result = submit(operation);
        executor.shutdown();
        return result;
    }
    protected String key(String type, String ip) { return namespace + ":" + type + ":" + ip; }
}
