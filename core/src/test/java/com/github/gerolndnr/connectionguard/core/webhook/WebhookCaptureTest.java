package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.extensions.*;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.google.gson.*;
import okhttp3.*;
import okio.Buffer;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual central capture/helper/queue with an isolated in-memory HTTP interceptor; no network. */
@Timeout(10)
class WebhookCaptureTest {
    private final List<JsonObject> bodies=new CopyOnWriteArrayList<>();
    private final Map<WebhookDispatcher,OkHttpClient> borrowed=new IdentityHashMap<>();
    private final CountDownLatch entered=new CountDownLatch(1);
    private volatile CountDownLatch release;
    private volatile int status=204;
    private volatile boolean failure;
    private Logger originalLogger;
    private static WebhookDispatcher owner()throws Exception {
        Field field=CGWebHookHelper.class.getDeclaredField("dispatcher");field.setAccessible(true);return (WebhookDispatcher)field.get(null);
    }
    private static void idle()throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<deadline){String value=CGWebHookHelper.describe();if(value.contains("queued=0") && value.contains("active=0"))return;Thread.sleep(1);}
        fail("Owned sender has not physically returned.");
    }
    private void borrow()throws Exception {
        WebhookDispatcher selected=owner();Field client=WebhookDispatcher.class.getDeclaredField("client");client.setAccessible(true);
        borrowed.put(selected,(OkHttpClient)client.get(selected));
        OkHttpClient fixture=new OkHttpClient.Builder().callTimeout(2500,TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addInterceptor(chain->{
                    assertEquals("example.invalid",chain.request().url().host());Buffer body=new Buffer();chain.request().body().writeTo(body);
                    bodies.add(JsonParser.parseString(body.readUtf8()).getAsJsonObject());entered.countDown();
                    try{CountDownLatch gate=release;if(gate!=null && !gate.await(3,TimeUnit.SECONDS))throw new java.io.IOException("Owned fixture expired.");}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.IOException("Owned fixture stopped.");}
                    if(failure)throw new java.io.IOException("synthetic-private-token");
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
                            .body(ResponseBody.create("",MediaType.get("application/json"))).build();
                }).build();
        client.set(selected,fixture);
    }
    private static GuardSettings settings(String mode) {
        Map<String,Object> values=new HashMap<>();values.put("operation.mode",mode);values.put("behavior.vpn.send-webhook.enabled",true);
        values.put("behavior.vpn.send-webhook.url","https://example.invalid/synthetic-private-token");values.put("behavior.vpn.send-webhook.format","EMBED");
        values.put("behavior.vpn.send-webhook.cooldown-ms",0);values.put("behavior.vpn.send-webhook.events",Arrays.asList("FLAG","DENY","ERROR","UNKNOWN"));
        return GuardSettings.read(values::get,Collections.emptyList());
    }
    @BeforeEach void start()throws Exception {
        idle();DecisionObservers.closeAll();originalLogger=ConnectionGuard.getLogger();ConnectionGuard.setLogger(null);
        ConnectionGuard.applySettings(settings("ENFORCE"));ConnectionGuard.setRequiredPositiveFlags(1);borrow();
    }
    @AfterEach void restore()throws Exception {
        if(release!=null)release.countDown();idle();
        Field client=WebhookDispatcher.class.getDeclaredField("client");client.setAccessible(true);
        for(Map.Entry<WebhookDispatcher,OkHttpClient> entry:borrowed.entrySet())client.set(entry.getKey(),entry.getValue());
        DecisionObservers.closeAll();ConnectionGuard.setRequiredPositiveFlags(1);ConnectionGuard.applySettings(GuardSettings.defaults());ConnectionGuard.setLogger(originalLogger);
    }
    private static DecisionCapture begin(){return DecisionCapture.begin(Platform.VELOCITY,Phase.LOGIN,"192.0.2.55",null,IdentityTrust.UNTRUSTED);}
    private static void flagged(DecisionCapture capture){capture.flag(Flag.VPN);capture.denied(Reason.VPN_FLAG);capture.close();}
    @Test void noAddonObserverIsNeededAndCloseEmitsExactlyOneFinalDecision()throws Exception {
        DecisionCapture capture=begin();flagged(capture);capture.close();idle();assertEquals(1,bodies.size());
        String value=bodies.get(0).toString();assertTrue(value.contains("DENY"));assertTrue(value.contains("VPN_FLAG"));assertFalse(value.contains("192.0.2.55"));
    }
    @Test void closeReturnsWhileTheIndependentDeliveryWorkerIsWaiting()throws Exception {
        release=new CountDownLatch(1);flagged(begin());assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertEquals(1,release.getCount());assertTrue(CGWebHookHelper.describe().contains("active=1"));
        // The login-side close already returned although the notification response remains gated.
    }
    @Test void capturedMinimumCannotChangeMidDecision()throws Exception {
        DecisionCapture capture=begin();ConnectionGuard.setRequiredPositiveFlags(2);flagged(capture);idle();
        assertTrue(bodies.get(0).toString().contains("configured minimum: 1"));
    }
    @Test void manualDenyBeforeLookupStillContainsTheSelectedRule()throws Exception {
        DecisionCapture capture=begin();AccessRule rule=new AccessRule("manual",AccessRule.Effect.DENY,AccessRule.Scope.VPN,"192.0.2.0/24",0,"synthetic-private-reason");
        capture.manual(Optional.of(rule),Optional.empty());capture.denied(Reason.ACCESS_RULE);capture.close();idle();
        String value=bodies.get(0).toString();assertTrue(value.contains("manual: DENY / VPN / MATCH (selected)"));assertTrue(value.contains("NOT_CHECKED"));assertFalse(value.contains("synthetic-private-reason"));
    }
    @Test void observerReceivesTheOriginalFactWhenDeliveryFails()throws Exception {
        failure=true;CompletableFuture<DecisionObservation> event=new CompletableFuture<>();
        try(ObserverRegistration ignored=ConnectionGuardApi.registerDecisionObserver("webhook-fixture",event::complete)) {
            Map<String,Object> fields=new HashMap<>();fields.put("integrations.observers.enabled",true);fields.put("integrations.observers.ids",Collections.singletonList("webhook-fixture"));
            DecisionObservers.configure(new ObserverSettings(fields::get));flagged(begin());
            DecisionObservation fact=event.get(2,TimeUnit.SECONDS);assertEquals(Outcome.DENY,fact.getOutcome());assertEquals(Reason.VPN_FLAG,fact.getReason());
            idle();assertEquals(1,bodies.size());
        }
    }
    @Test void observeModeAndDisabledRecipientsNeverEnterTheSender()throws Exception {
        ConnectionGuard.applySettings(settings("OBSERVE"));flagged(begin());idle();assertEquals(0,bodies.size());
        ConnectionGuard.applySettings(GuardSettings.defaults());flagged(begin());idle();assertEquals(0,bodies.size());
    }
    @Test void changedDraftRetiresAQueuedMessageEvenWhenNewDraftHasTheSameFingerprint()throws Exception {
        release=new CountDownLatch(1);flagged(begin());assertTrue(entered.await(2,TimeUnit.SECONDS));
        GuardSettings before=ConnectionGuard.getSettings();flagged(begin());GuardSettings next=settings("ENFORCE");
        assertEquals(before.webhooks.fingerprint(),next.webhooks.fingerprint());assertNotSame(before.webhooks,next.webhooks);
        ConnectionGuard.applySettings(next);release.countDown();idle();assertEquals(1,bodies.size());
    }
    @Test void lateOldCaptureCannotSendAfterReload()throws Exception {
        DecisionCapture old=begin();ConnectionGuard.applySettings(settings("ENFORCE"));flagged(old);idle();assertEquals(0,bodies.size());
    }
    @Test void rejectedConfigurationDoesNotRetireThePreviouslyValidSelection()throws Exception {
        GuardSettings before=ConnectionGuard.getSettings();Map<String,Object> invalid=new HashMap<>();invalid.put("behavior.vpn.send-webhook.cooldown-ms",60001);
        assertThrows(IllegalArgumentException.class,()->ConnectionGuard.applySettings(GuardSettings.read(invalid::get,Collections.emptyList())));
        assertSame(before,ConnectionGuard.getSettings());flagged(begin());idle();assertEquals(1,bodies.size());
    }
    @Test void reenableWaitsForPhysicalRetirementAndCreatesOneNewOwner()throws Exception {
        WebhookDispatcher old=owner();CGWebHookHelper.shutdown();idle();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!old.isTerminated() && System.nanoTime()<deadline)Thread.sleep(1);
        assertTrue(old.isTerminated());ConnectionGuard.applySettings(settings("ENFORCE"));assertNotSame(old,owner());borrow();
        flagged(begin());idle();assertEquals(1,bodies.size());assertTrue(old.isTerminated());
    }
    @Test void errorOnlySelectionCanReportAnErrorAlongsideADeny()throws Exception {
        Map<String,Object> fields=new HashMap<>();fields.put("behavior.vpn.send-webhook.enabled",true);fields.put("behavior.vpn.send-webhook.url","https://example.invalid/synthetic-private-token");
        fields.put("behavior.vpn.send-webhook.format","EMBED");fields.put("behavior.vpn.send-webhook.events",Collections.singletonList("ERROR"));fields.put("behavior.vpn.send-webhook.cooldown-ms",0);
        ConnectionGuard.applySettings(GuardSettings.read(fields::get,Collections.emptyList()));DecisionCapture capture=begin();capture.denied(Reason.LOOKUP_UNAVAILABLE);capture.error();capture.close();idle();
        assertEquals(1,bodies.size());assertTrue(bodies.get(0).toString().contains("Processing error: true"));assertTrue(bodies.get(0).toString().contains("DENY"));
    }
    @Test void invalidPayloadDoesNotSendOrLeakTheProvidedValue()throws Exception {
        CGWebHookHelper.sendDecision(new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.UNTRUSTED,null,"192.0.2.55",Outcome.DENY,Reason.VPN_FLAG,Check.POSITIVE,Check.EXEMPT,1000,0,false,EnumSet.of(Flag.VPN),Collections.emptyList(),Collections.emptyList()),ConnectionGuard.getSettings().webhooks,0);
        idle();assertEquals(0,bodies.size());assertTrue(CGWebHookHelper.describe().contains("invalid="));
    }
    @Test void compatibilityTextEntryPointStillSuppressesAllMentionParsing()throws Exception {
        CGWebHookHelper.sendWebHook("https://example.invalid/synthetic-private-token","@everyone synthetic").get(2,TimeUnit.SECONDS);idle();
        assertEquals("@everyone synthetic",bodies.get(0).get("content").getAsString());assertEquals(0,bodies.get(0).getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
    }
    @Test void repeatedDeliveryFailureWarningsAreBoundedAndRedacted()throws Exception {
        List<String> messages=new ArrayList<>();Logger logger=Logger.getAnonymousLogger();logger.setUseParentHandlers(false);
        logger.addHandler(new Handler(){public void publish(LogRecord record){messages.add(record.getMessage());}public void flush(){}public void close(){}});
        ConnectionGuard.setLogger(logger);failure=true;
        for(int i=0;i<4;i++){flagged(begin());idle();}
        assertEquals(4,bodies.size());assertTrue(messages.size()<=1);
        for(String message:messages){assertFalse(message.contains("synthetic-private-token"));assertFalse(message.contains("example.invalid"));}
    }
}
