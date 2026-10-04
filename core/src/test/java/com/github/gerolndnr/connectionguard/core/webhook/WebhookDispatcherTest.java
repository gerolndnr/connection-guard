package com.github.gerolndnr.connectionguard.core.webhook;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Owned loopback fixture; no Discord account, endpoint or credential is used. */
@Timeout(8)
class WebhookDispatcherTest {
    private HttpServer server;private WebhookDispatcher dispatcher;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<String> received=new AtomicReference<>();
    private final CountDownLatch entered=new CountDownLatch(1);
    private volatile CountDownLatch release;
    private volatile int status=204;
    private volatile String body="";
    private final Map<String,String> headers=new ConcurrentHashMap<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();received.set(new String(read(exchange.getRequestBody()),StandardCharsets.UTF_8));entered.countDown();
            try {
                CountDownLatch gate=release;if(gate!=null && !gate.await(4,TimeUnit.SECONDS))throw new IllegalStateException("Owned fixture gate expired.");
                headers.forEach((key,value)->exchange.getResponseHeaders().add(key,value));
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,status==204?-1:bytes.length);
                if(status!=204)exchange.getResponseBody().write(bytes);
            } catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(java.io.IOException disconnected){/* Cancellation/timeout is intentional. */}
            finally{exchange.close();}
        });server.start();dispatcher=new WebhookDispatcher();
    }
    private static byte[] read(java.io.InputStream input)throws java.io.IOException {
        java.io.ByteArrayOutputStream result=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
        while((count=input.read(buffer))!=-1){result.write(buffer,0,count);if(result.size()>65536)throw new java.io.IOException("Fixture body exceeded.");}
        return result.toByteArray();
    }
    private String endpoint(String path){return "http://127.0.0.1:"+server.getAddress().getPort()+path;}
    private CompletableFuture<WebhookDispatcher.Result> submit(String path,int cooldown){return dispatcher.submit(endpoint(path),"{\"content\":\"synthetic\"}",cooldown,()->true);}
    private static WebhookDispatcher.Result result(CompletableFuture<WebhookDispatcher.Result> future)throws Exception{return future.get(4,TimeUnit.SECONDS);}
    @AfterEach void stop(){if(release!=null)release.countDown();dispatcher.close();server.stop(0);}
    @Test void actualAcceptanceIsBestEffortAndCooldownSendsOneRequest()throws Exception {
        assertEquals(WebhookDispatcher.Result.SENT,result(submit("/one",60000)));
        assertEquals(WebhookDispatcher.Result.COOLDOWN,result(submit("/one",60000)));assertEquals(1,calls.get());
        assertEquals("{\"content\":\"synthetic\"}",received.get());
        assertFalse(dispatcher.describe().contains(endpoint("/one")));
    }
    @ParameterizedTest @ValueSource(ints={302,400,401,403,500})
    void actualErrorsAndRedirectsDoNotRetry(int code)throws Exception {
        status=code;headers.put("Location",endpoint("/redirect"));
        assertEquals(WebhookDispatcher.Result.HTTP_ERROR,result(submit("/one",0)));assertEquals(1,calls.get());
    }
    @Test void headerRateLimitPausesWithoutRetryingEvenWithInvalidBody()throws Exception {
        status=429;body="synthetic invalid body";headers.put("Retry-After","2.5");
        assertEquals(WebhookDispatcher.Result.RATE_LIMITED,result(submit("/one",0)));
        assertEquals(WebhookDispatcher.Result.RATE_LIMITED,result(submit("/one",0)));assertEquals(1,calls.get());
    }
    @Test void jsonOnlyGlobalRateLimitPausesADifferentEndpoint()throws Exception {
        status=429;body="{\"retry_after\":2.125,\"global\":true}";
        assertEquals(WebhookDispatcher.Result.RATE_LIMITED,result(submit("/one",0)));
        assertEquals(WebhookDispatcher.Result.RATE_LIMITED,result(submit("/other",0)));assertEquals(1,calls.get());
    }
    @Test void depletedSuccessBucketPausesOnlyItsEndpoint()throws Exception {
        headers.put("X-RateLimit-Remaining","0");headers.put("X-RateLimit-Reset-After","3.125");
        assertEquals(WebhookDispatcher.Result.SENT,result(submit("/one",0)));
        assertEquals(WebhookDispatcher.Result.RATE_LIMITED,result(submit("/one",0)));
        assertEquals(WebhookDispatcher.Result.SENT,result(submit("/other",0)));assertEquals(2,calls.get());
    }
    @Test void timeoutLeavesAmbiguousResultAndMakesNoAutomaticSecondPost()throws Exception {
        release=new CountDownLatch(1);
        assertEquals(WebhookDispatcher.Result.UNKNOWN,result(submit("/one",0)));assertEquals(1,calls.get());
    }
    @Test void oneActivePlusSixteenWaitingIsBoundedAndCloseCompletesAllWaitingFutures()throws Exception {
        release=new CountDownLatch(1);CompletableFuture<WebhookDispatcher.Result> active=submit("/one",0);
        assertTrue(entered.await(2,TimeUnit.SECONDS));List<CompletableFuture<WebhookDispatcher.Result>> queued=new ArrayList<>();
        for(int i=0;i<16;i++)queued.add(submit("/one",0));
        assertEquals(WebhookDispatcher.Result.OVERLOADED,result(submit("/one",0)));
        dispatcher.close();assertEquals(WebhookDispatcher.Result.SHUTDOWN,result(active));
        for(CompletableFuture<WebhookDispatcher.Result> future:queued)assertEquals(WebhookDispatcher.Result.SHUTDOWN,result(future));
        assertEquals(WebhookDispatcher.Result.SHUTDOWN,result(submit("/other",0)));assertEquals(1,calls.get());
    }
    @Test void changedConfigurationRetiresAQueuedRecipientBeforeHttp()throws Exception {
        release=new CountDownLatch(1);CompletableFuture<WebhookDispatcher.Result> first=submit("/one",0);
        assertTrue(entered.await(2,TimeUnit.SECONDS));AtomicBoolean current=new AtomicBoolean(true);
        CompletableFuture<WebhookDispatcher.Result> queued=dispatcher.submit(endpoint("/old-secret"),"{}",0,current::get);
        current.set(false);release.countDown();assertEquals(WebhookDispatcher.Result.SENT,result(first));
        assertEquals(WebhookDispatcher.Result.RETIRED,result(queued));assertEquals(1,calls.get());
        assertFalse(dispatcher.describe().contains("old-secret"));
    }
    @Test void recipientRetiredImmediatelyBeforePostDoesNotRetainTheCancelledCall()throws Exception {
        AtomicInteger gates=new AtomicInteger();
        assertEquals(WebhookDispatcher.Result.RETIRED,result(dispatcher.submit(endpoint("/old"),"{}",0,()->gates.incrementAndGet()==1)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!dispatcher.describe().contains("active=0") && System.nanoTime()<deadline)Thread.sleep(1);
        java.lang.reflect.Field active=WebhookDispatcher.class.getDeclaredField("active");active.setAccessible(true);
        assertNull(((AtomicReference<?>)active.get(dispatcher)).get());assertEquals(0,calls.get());
    }
    @Test void outOfBoundsBodyDoesNotEnterQueueOrHttp()throws Exception {
        char[] large=new char[65537];Arrays.fill(large,'x');
        assertEquals(WebhookDispatcher.Result.RETIRED,result(dispatcher.submit(endpoint("/one"),new String(large),0,()->true)));
        assertEquals(0,calls.get());
    }
    @Test void retryDecimalBoundsAreConservativeAndNeverOverflow() {
        assertEquals(2125,WebhookDispatcher.retryMillis("2.125"));assertEquals(1000,WebhookDispatcher.retryMillis("0.001"));
        assertEquals(3600000,WebhookDispatcher.retryMillis("9999999999999999999999999999999"));
        for(String malformed:Arrays.asList(null,"NaN","-1","1e999999","0.0000001"))assertEquals(60000,WebhookDispatcher.retryMillis(malformed));
    }
}
