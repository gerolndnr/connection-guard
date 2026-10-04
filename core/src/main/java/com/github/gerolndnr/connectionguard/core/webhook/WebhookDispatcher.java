package com.github.gerolndnr.connectionguard.core.webhook;

import okhttp3.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** One worker and sixteen waiting messages. Failed/ambiguous POSTs are never retried. */
final class WebhookDispatcher implements AutoCloseable {
    enum Result { SENT, HTTP_ERROR, RATE_LIMITED, COOLDOWN, UNKNOWN, OVERLOADED, RETIRED, SHUTDOWN }
    private final OkHttpClient client;
    private final ThreadPoolExecutor executor;
    private final Map<String,Long> cooldowns=new LinkedHashMap<>(), pauses=new LinkedHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<Call> active=new AtomicReference<>();
    private final AtomicLong sent=new AtomicLong(),failed=new AtomicLong(),skipped=new AtomicLong();
    private volatile long globalPause;
    WebhookDispatcher() { this(new OkHttpClient.Builder().callTimeout(2500,TimeUnit.MILLISECONDS).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).build()); }
    /** Owned fixture seam; production always uses the bounded, validating TLS client above. */
    WebhookDispatcher(OkHttpClient client) {
        this.client=client.newBuilder().callTimeout(2500,TimeUnit.MILLISECONDS).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).build();
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),task->{
            Thread thread=new Thread(task,"ConnectionGuard-webhooks");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    CompletableFuture<Result> submit(String endpoint,String body,int cooldown,BooleanSupplier current) {
        if(body==null || body.length()>65536 || cooldown<0 || cooldown>60000) return CompletableFuture.completedFuture(Result.RETIRED);
        Job job=new Job(endpoint,body,cooldown,current);
        if(closed.get()) {job.finish(Result.SHUTDOWN);return job.future;}
        try {executor.execute(job);} catch(RejectedExecutionException rejected){job.finish(closed.get()?Result.SHUTDOWN:Result.OVERLOADED);}
        return job.future;
    }
    private final class Job implements Runnable {
        private final String endpoint,body;private final int cooldown;private final BooleanSupplier current;
        private final CompletableFuture<Result> future=new CompletableFuture<>();
        private Job(String endpoint,String body,int cooldown,BooleanSupplier current){this.endpoint=endpoint;this.body=body;this.cooldown=cooldown;this.current=current;}
        private void finish(Result result) {
            if(future.complete(result)) {if(result==Result.SENT)sent.incrementAndGet();else if(result==Result.UNKNOWN || result==Result.HTTP_ERROR || result==Result.RATE_LIMITED)failed.incrementAndGet();else skipped.incrementAndGet();}
        }
        @Override public void run() {
            if(closed.get()){finish(Result.SHUTDOWN);return;}
            try {
                if(!current.getAsBoolean()){finish(Result.RETIRED);return;}
                long now=System.currentTimeMillis();String id=WebhookSettings.digest(endpoint);
                if(now<globalPause || now<pauses.getOrDefault(id,0L)){finish(Result.RATE_LIMITED);return;}
                if(now<cooldowns.getOrDefault(id,0L)){finish(Result.COOLDOWN);return;}
                remember(cooldowns,id,now+cooldown);
                Request request=new Request.Builder().url(endpoint).post(RequestBody.create(body,MediaType.get("application/json"))).build();
                Call call=client.newCall(request);active.set(call);
                try {
                    if(closed.get() || !current.getAsBoolean()) {call.cancel();finish(closed.get()?Result.SHUTDOWN:Result.RETIRED);return;}
                    try(Response response=call.execute()) {
                        if(response.code()==429) {
                            RateLimit limit=rateLimit(response);
                            long until=System.currentTimeMillis()+limit.pause;
                            remember(pauses,id,until);if(limit.global)globalPause=until;
                            finish(Result.RATE_LIMITED);
                        } else if(response.isSuccessful()) {
                            // HTTP acceptance cannot prove Discord persisted a message with wait=false.
                            if("0".equals(response.header("X-RateLimit-Remaining")))remember(pauses,id,System.currentTimeMillis()+retryMillis(response.header("X-RateLimit-Reset-After")));
                            finish(Result.SENT);
                        } else finish(Result.HTTP_ERROR);
                    }
                } finally {active.compareAndSet(call,null);}
            } catch(IOException | RuntimeException failure) {finish(closed.get()?Result.SHUTDOWN:Result.UNKNOWN);}
        }
    }
    private static final class RateLimit {
        private final long pause;private final boolean global;
        private RateLimit(long pause,boolean global){this.pause=pause;this.global=global;}
    }
    private static RateLimit rateLimit(Response response) {
        String seconds=response.header("Retry-After");
        boolean global="true".equalsIgnoreCase(response.header("X-RateLimit-Global"))
                || "global".equalsIgnoreCase(response.header("X-RateLimit-Scope"));
        String bodySeconds=null;boolean bodyGlobal=false;
        // Bound bytes, token count and numeric text; never retain/log arbitrary response content.
        try(ResponseBody body=response.peekBody(4097)) {
            byte[] bytes=body.bytes();
            if(bytes.length<=4096)try(JsonReader reader=new JsonReader(new StringReader(new String(bytes,StandardCharsets.UTF_8)))) {
                reader.beginObject();Set<String> names=new HashSet<>();int count=0;
                while(reader.hasNext()) {
                    if(++count>16)throw new IOException("Bounded rate-limit response exceeded.");
                    String name=reader.nextName();if(!names.add(name))throw new IOException("Duplicate rate-limit field.");
                    if(name.equals("retry_after") && reader.peek()==JsonToken.NUMBER) {
                        String value=reader.nextString();if(value.length()<=32)bodySeconds=value;
                    } else if(name.equals("global") && reader.peek()==JsonToken.BOOLEAN)bodyGlobal=reader.nextBoolean();
                    else reader.skipValue();
                }
                reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Invalid rate-limit response.");
            }
        } catch(IOException | RuntimeException invalid) {bodySeconds=null;bodyGlobal=false;}
        return new RateLimit(retryMillis(seconds==null?bodySeconds:seconds),global || bodyGlobal);
    }
    private static void remember(Map<String,Long> values,String key,long until) {
        values.put(key,until);while(values.size()>32)values.remove(values.keySet().iterator().next());
    }
    static long retryMillis(String seconds) {
        try {
            if(seconds==null || seconds.length()>32) return 60000;
            BigDecimal value=new BigDecimal(seconds);
            if(value.scale() < -6 || value.scale()>6 || value.signum()<0) return 60000;
            return value.multiply(BigDecimal.valueOf(1000)).max(BigDecimal.valueOf(1000)).min(BigDecimal.valueOf(3600000)).longValue();
        } catch(RuntimeException invalid){return 60000;}
    }
    String describe(){return "webhooks sent="+sent.get()+" failed="+failed.get()+" skipped="+skipped.get()+" queued="+executor.getQueue().size()+" active="+executor.getActiveCount()+" (best effort; no retries)";}
    boolean isClosed(){return closed.get();}
    boolean isTerminated(){return executor.isTerminated();}
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        Call call=active.get();if(call!=null)call.cancel();
        for(Runnable task:executor.shutdownNow())if(task instanceof WebhookDispatcher.Job)((Job)task).finish(Result.SHUTDOWN);
    }
}
