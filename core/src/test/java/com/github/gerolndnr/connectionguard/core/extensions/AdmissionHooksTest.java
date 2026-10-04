package com.github.gerolndnr.connectionguard.core.extensions;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.*;
import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
/** Synthetic contract tests; do not claim installed native SDK/authentication qualification. */
@Timeout(10) class AdmissionHooksTest {
    final List<CompletableFuture<AdmissionResponse>> pending=new ArrayList<>();
    final UUID uuid=UUID.fromString("dddddddd-1111-4222-8333-444444444444");
    final AtomicInteger detection=new AtomicInteger();
    @BeforeEach void setup(){
        AdmissionHooks.closeAll();
        ConnectionGuard.setCacheProvider(new NoCacheProvider());ConnectionGuard.setRequiredPositiveFlags(1);ConnectionGuard.setGeoProvider(null);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip->{detection.incrementAndGet();return CompletableFuture.completedFuture(Optional.of(new VpnResult(ip,true)));})));
    }
    @AfterEach void cleanup()throws Exception{
        AdmissionHooks.closeAll();for(CompletableFuture<AdmissionResponse> f:pending)f.complete(AdmissionResponse.unknown(AdmissionResponse.Reason.CANCELLED));
        long due=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while((!ConnectionGuard.getLookupRuntime().isIdle() || AdmissionHooks.inflight()!=0 || LoginChecks.active()!=0) && System.nanoTime()<due)Thread.sleep(2);
        assertEquals(0,AdmissionHooks.inflight());assertEquals(0,LoginChecks.active());assertTrue(ConnectionGuard.getLookupRuntime().isIdle());
        ConnectionGuard.applySettings(GuardSettings.defaults());
    }
    GuardSettings configure(boolean closed,String...ids){return configure(closed,200,ids);}
    GuardSettings configure(boolean closed,long budgetMillis,String...ids){
        Map<String,Object> values=new HashMap<>();values.put("operation.mode","ENFORCE");values.put("lookup.deadline-ms",budgetMillis);values.put("lookup.http-timeout-ms",100);
        values.put("integrations.admission.enabled",ids.length>0);values.put("integrations.admission.ids",Arrays.asList(ids));values.put("integrations.admission.failure-policy",closed?"CLOSED":"OPEN");
        GuardSettings settings=GuardSettings.read(values::get,Collections.emptyList());ConnectionGuard.applySettings(settings);return settings;
    }
    AdmissionRequest request(long started){return request(started,200);}
    AdmissionRequest request(long started,long budgetMillis){return new AdmissionRequest("192.0.2.211",uuid,DecisionObservation.IdentityTrust.AUTHENTICATED,DecisionObservation.Platform.VELOCITY,started+TimeUnit.MILLISECONDS.toNanos(budgetMillis));}
    void awaitNativeIdle()throws InterruptedException{
        long due=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while((AdmissionHooks.inflight()!=0 || LoginChecks.active()!=0 || !ConnectionGuard.getLookupRuntime().isIdle()) && System.nanoTime()<due)Thread.sleep(2);
        assertEquals(0,AdmissionHooks.inflight(),"Native callback completions must release their physical slots.");
        assertEquals(0,LoginChecks.active(),"Login completion must release its caller slot.");
        assertTrue(ConnectionGuard.getLookupRuntime().isIdle(),"Fixture workers/deadlines must stop before the next iteration.");
    }
    LoginChecks.Result check(boolean observe,LoginChecks.Permission vpn)throws Exception{
        long start=System.nanoTime();return LoginChecks.check("192.0.2.211",ConnectionGuard.getLookupRuntime().getSettings(),start,observe,vpn,LoginChecks.Permission.known(true),request(start)).get(2,TimeUnit.SECONDS);
    }
    @Test void selectionIsExplicitValidatedAndRedactsInvalidValues(){
        AtomicInteger calls=new AtomicInteger();AdmissionHooks.register("test",r->{calls.incrementAndGet();return CompletableFuture.completedFuture(AdmissionResponse.activeBan(0));});
        GuardSettings defaults=GuardSettings.defaults();assertTrue(defaults.admissionHooks.ids.isEmpty());
        Map<String,Object> values=new HashMap<>();values.put("integrations.admission.ids",Arrays.asList("private invalid value"));
        Exception error=assertThrows(IllegalArgumentException.class,()->GuardSettings.read(values::get,Collections.emptyList()));assertFalse(error.getMessage().contains("private invalid"));
        values.put("integrations.admission.ids",Arrays.asList("test","test"));assertThrows(IllegalArgumentException.class,()->GuardSettings.read(values::get,Collections.emptyList()));assertEquals(0,calls.get());
    }
    @Test void claimedIdentityCannotBeIncludedAsVerifiedUuid(){
        assertThrows(IllegalArgumentException.class,()->new AdmissionRequest("192.0.2.211",uuid,DecisionObservation.IdentityTrust.FORWARDED,DecisionObservation.Platform.VELOCITY,System.nanoTime()));
        assertThrows(IllegalArgumentException.class,()->new AdmissionRequest("fixture.invalid",null,DecisionObservation.IdentityTrust.UNTRUSTED,DecisionObservation.Platform.VELOCITY,System.nanoTime()));
        assertFalse(new AdmissionRequest("192.0.2.211",null,DecisionObservation.IdentityTrust.UNTRUSTED,DecisionObservation.Platform.VELOCITY,System.nanoTime()).getVerifiedUuid().isPresent());
    }
    @Test void activeNativeBanStopsBeforePermissionFactoryAndDetectors()throws Exception{
        AtomicInteger permissions=new AtomicInteger();AdmissionHooks.register("test",r->{assertEquals(uuid,r.getVerifiedUuid().get());return CompletableFuture.completedFuture(AdmissionResponse.activeBan(0));});configure(false,"test");
        LoginChecks.Result result=check(false,LoginChecks.Permission.lookup(()->{permissions.incrementAndGet();return CompletableFuture.completedFuture(true);}));
        assertTrue(result.external().shouldRefuse(false));assertEquals(DecisionObservation.Reason.EXTERNAL_POLICY,result.external().refusalReason());assertEquals(0,permissions.get());assertEquals(0,detection.get());
    }
    @Test void clearDoesNotBypassNormalVpnChecks()throws Exception{
        AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.clear()));configure(true,"test");LoginChecks.Result result=check(false,LoginChecks.Permission.known(false));
        assertFalse(result.external().shouldRefuse(false));assertEquals(ProviderVote.Status.POSITIVE,result.vpn().getStatus());assertEquals(1,detection.get());
    }
    @Test void observeNeverRefusesForNativeBanAndStillChecksNormalPolicy()throws Exception{
        AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.activeBan(0)));configure(true,"test");LoginChecks.Result result=check(true,LoginChecks.Permission.known(false));
        assertTrue(result.external().isDenied());assertFalse(result.external().shouldRefuse(true));assertEquals(1,detection.get());
    }
    @Test void expiredNativeBanIsUnknownNotAnActiveBanOrExemption()throws Exception{
        AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.activeBan(System.currentTimeMillis()-1)));configure(false,"test");LoginChecks.Result result=check(false,LoginChecks.Permission.known(false));
        assertEquals(AdmissionResponse.Reason.STALE_DATA,result.external().observations.get(0).getResponse().getReason());assertFalse(result.external().isDenied());assertEquals(1,detection.get());
    }
    @Test void absentModuleAndAbsentContextStayUnknownEvenForKnownExemptions()throws Exception{
        configure(true,"missing");LoginChecks.Result result=check(false,LoginChecks.Permission.known(true));assertTrue(result.external().shouldRefuse(false));assertEquals(DecisionObservation.Reason.EXTERNAL_UNAVAILABLE,result.external().refusalReason());
        configure(false,"missing");result=check(false,LoginChecks.Permission.known(false));assertFalse(result.external().shouldRefuse(false));assertEquals(1,detection.get());
        AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.clear()));configure(true,"test");long start=System.nanoTime();result=LoginChecks.check("192.0.2.211",ConnectionGuard.getLookupRuntime().getSettings(),start,false,LoginChecks.Permission.known(true),LoginChecks.Permission.known(true)).get(2,TimeUnit.SECONDS);
        assertTrue(result.external().shouldRefuse(false));
    }
    @Test void lateNativeReadCannotAlterCompletedLoginOrReleasePhysicalSlotEarly()throws Exception{
        CompletableFuture<AdmissionResponse> nativeRead=new CompletableFuture<>();pending.add(nativeRead);CountDownLatch started=new CountDownLatch(1);
        AdmissionHooks.register("test",r->{started.countDown();return nativeRead;});configure(true,"test");
        LoginChecks.Result result=check(false,LoginChecks.Permission.known(true));assertTrue(started.await(1,TimeUnit.SECONDS));assertTrue(result.isExpired());assertTrue(result.external().shouldRefuse(false));assertEquals(1,AdmissionHooks.inflight());
        assertThrows(IllegalStateException.class,()->ConnectionGuard.applySettings(GuardSettings.defaults()));
        nativeRead.complete(AdmissionResponse.clear());assertEquals(AdmissionResponse.Reason.TIMEOUT,result.external().observations.get(0).getResponse().getReason());assertEquals(0,detection.get());
    }
    @Test void unregisterDuringDetectionRemovesEarlierClearAuthority()throws Exception{
        AdmissionRegistration handle=AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.clear()));configure(true,"test");
        CompletableFuture<Optional<VpnResult>> source=new CompletableFuture<>();CountDownLatch started=new CountDownLatch(1);ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip->{started.countDown();return source;})));
        long start=System.nanoTime();CompletableFuture<LoginChecks.Result> work=LoginChecks.check("192.0.2.211",ConnectionGuard.getLookupRuntime().getSettings(),start,false,LoginChecks.Permission.known(false),LoginChecks.Permission.known(true),request(start));
        try{assertTrue(started.await(1,TimeUnit.SECONDS));handle.close();source.complete(Optional.of(new VpnResult("192.0.2.211",false)));LoginChecks.Result result=work.get(2,TimeUnit.SECONDS);assertTrue(result.external().shouldRefuse(false));assertEquals(AdmissionResponse.Reason.UNAVAILABLE,result.external().observations.get(0).getResponse().getReason());}
        finally{source.complete(Optional.empty());}
    }
    @Test void physicalReadLimitSurvivesTimeoutUnregisterAndSameIdReplacement()throws Exception{
        CountDownLatch started=new CountDownLatch(32);AdmissionRegistration old=AdmissionHooks.register("test",r->{CompletableFuture<AdmissionResponse> f=new CompletableFuture<>();synchronized(pending){pending.add(f);}started.countDown();return f;});configure(true,"test");
        AdmissionHooks.Snapshot snapshot=AdmissionHooks.snapshot();AdmissionRequest request=new AdmissionRequest("192.0.2.211",null,DecisionObservation.IdentityTrust.UNTRUSTED,DecisionObservation.Platform.VELOCITY,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
        List<CompletableFuture<AdmissionObservation>> reads=new ArrayList<>();for(int i=0;i<32;i++)reads.add(snapshot.invoke(0,request,ConnectionGuard.getLookupRuntime()));
        assertTrue(started.await(2,TimeUnit.SECONDS));assertEquals(32,AdmissionHooks.inflight());assertEquals(AdmissionResponse.Reason.OVERLOADED,snapshot.invoke(0,request,ConnectionGuard.getLookupRuntime()).get().getResponse().getReason());
        old.close();AdmissionRegistration replacement=AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.clear()));old.close();assertTrue(replacement.isRegistered());assertThrows(IllegalStateException.class,()->configure(true,"test"));
        for(CompletableFuture<AdmissionResponse> f:pending)f.complete(AdmissionResponse.clear());for(CompletableFuture<AdmissionObservation> f:reads)assertEquals(AdmissionResponse.Reason.UNAVAILABLE,f.get(1,TimeUnit.SECONDS).getResponse().getReason());assertEquals(0,AdmissionHooks.inflight());
    }
    @Test void earlyDenialThatLosesItsSourceCannotAllowUncheckedLoginEvenWhenOpen()throws Exception{
        AdmissionRegistration handle=AdmissionHooks.register("test",r->CompletableFuture.completedFuture(AdmissionResponse.activeBan(0)));configure(false,"test");
        LoginChecks.Result result=check(false,LoginChecks.Permission.known(false));assertTrue(result.external().isDenied());handle.close();
        assertFalse(result.external().isDenied());assertEquals(AdmissionResponse.Reason.UNAVAILABLE,result.external().observations.get(0).getResponse().getReason());
        assertTrue(result.external().shouldRefuse(false));assertEquals(DecisionObservation.Reason.EXTERNAL_UNAVAILABLE,result.external().refusalReason());assertEquals(0,detection.get());
    }
    @Test void concurrentClearAndDenyNeverStartPermissionsOrDetection()throws Exception{
        AtomicInteger permissions=new AtomicInteger();
        for(int iteration=0;iteration<40;iteration++){
            AdmissionHooks.closeAll();CompletableFuture<AdmissionResponse> clear=new CompletableFuture<>(),deny=new CompletableFuture<>();pending.add(clear);pending.add(deny);
            CountDownLatch called=new CountDownLatch(2);
            AdmissionHooks.register("one",r->{called.countDown();return clear;});AdmissionHooks.register("two",r->{called.countDown();return deny;});configure(false,"one","two");
            long start=System.nanoTime();CompletableFuture<LoginChecks.Result> work=LoginChecks.check("192.0.2.211",ConnectionGuard.getLookupRuntime().getSettings(),start,false,
                LoginChecks.Permission.lookup(()->{permissions.incrementAndGet();return CompletableFuture.completedFuture(false);}),LoginChecks.Permission.known(true),request(start));
            assertTrue(called.await(1,TimeUnit.SECONDS));CountDownLatch go=new CountDownLatch(1);
            CompletableFuture<Void> a=CompletableFuture.runAsync(()->{try{go.await();clear.complete(AdmissionResponse.clear());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}});
            CompletableFuture<Void> b=CompletableFuture.runAsync(()->{try{go.await();deny.complete(AdmissionResponse.activeBan(0));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}});
            go.countDown();LoginChecks.Result result=work.get(1,TimeUnit.SECONDS);CompletableFuture.allOf(a,b).get(1,TimeUnit.SECONDS);
            assertTrue(result.external().shouldRefuse(false));
            // Completing the callback's returned future need not mean its worker has returned.
            awaitNativeIdle();
            assertEquals(0,permissions.get(),"A concurrent native DENY must not launch permissions.");
            assertEquals(0,detection.get(),"A concurrent native DENY must not launch detection.");
            assertEquals(0,AdmissionHooks.inflight());
        }
    }
    @Test void completedNativeFutureRetainsPhysicalSlotUntilCallbackReturns()throws Exception{
        AtomicInteger permissions=new AtomicInteger();
        CompletableFuture<AdmissionResponse> clear=new CompletableFuture<>(),deny=new CompletableFuture<>();pending.add(clear);pending.add(deny);
        CountDownLatch called=new CountDownLatch(2),allowReturn=new CountDownLatch(1);
        AdmissionHooks.register("one",r->{
            called.countDown();
            try{if(!allowReturn.await(3,TimeUnit.SECONDS))throw new AssertionError("Native callback fixture gate timed out.");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}
            return clear;
        });
        AdmissionHooks.register("two",r->{called.countDown();return deny;});configure(false,5000,"one","two");
        long start=System.nanoTime();CompletableFuture<LoginChecks.Result> work=LoginChecks.check("192.0.2.211",ConnectionGuard.getLookupRuntime().getSettings(),start,false,
            LoginChecks.Permission.lookup(()->{permissions.incrementAndGet();return CompletableFuture.completedFuture(false);}),LoginChecks.Permission.known(true),request(start,5000));
        try{
            assertTrue(called.await(1,TimeUnit.SECONDS));clear.complete(AdmissionResponse.clear());deny.complete(AdmissionResponse.activeBan(0));
            LoginChecks.Result result=work.get(1,TimeUnit.SECONDS);
            assertTrue(result.external().isDenied());assertTrue(result.external().shouldRefuse(false));
            assertEquals(0,permissions.get());assertEquals(0,detection.get());assertEquals(0,LoginChecks.active());
            assertEquals(1,AdmissionHooks.inflight(),"A completed future cannot release a still-executing native callback.");
            assertThrows(IllegalStateException.class,()->ConnectionGuard.applySettings(GuardSettings.defaults()));
        }finally{
            clear.complete(AdmissionResponse.clear());deny.complete(AdmissionResponse.activeBan(0));allowReturn.countDown();
            awaitNativeIdle();
        }
        assertEquals(0,permissions.get());assertEquals(0,detection.get());
    }
    @Test void cancelledGuardRuntimeStillRequiresClosedNativeRefusal()throws Exception{
        AtomicInteger nativeCalls=new AtomicInteger();AdmissionHooks.register("test",r->{nativeCalls.incrementAndGet();return CompletableFuture.completedFuture(AdmissionResponse.clear());});configure(true,"test");
        ConnectionGuard.getLookupRuntime().close();LoginChecks.Result result=check(false,LoginChecks.Permission.known(true));
        assertTrue(result.isCancelled());assertTrue(result.external().shouldRefuse(false));assertEquals(AdmissionResponse.Reason.CANCELLED,result.external().observations.get(0).getResponse().getReason());
        assertEquals(DecisionObservation.Reason.EXTERNAL_UNAVAILABLE,result.external().refusalReason());assertEquals(0,nativeCalls.get());assertEquals(0,detection.get());
    }
    @Test void callbackFailuresAreTypedAndNeverBecomeClear()throws Exception{
        AdmissionHooks.register("test",r->{throw new AssertionError("synthetic private failure");});configure(true,"test");LoginChecks.Result result=check(false,LoginChecks.Permission.known(true));
        assertTrue(result.external().shouldRefuse(false));assertEquals(AdmissionResponse.Reason.INVALID_RESPONSE,result.external().observations.get(0).getResponse().getReason());assertEquals(0,AdmissionHooks.inflight());
    }
}
