package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class DeferredDecisionCaptureTest {
    CountDownLatch occupied=new CountDownLatch(2),release=new CountDownLatch(1);
    @BeforeEach void setup(){DecisionObservers.closeAll();ConnectionGuard.applySettings(GuardSettings.defaults());}
    @AfterEach void cleanup()throws Exception{release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(DecisionObservers.pendingCaptures()>0 && System.nanoTime()<end)Thread.sleep(2);assertEquals(0,DecisionObservers.pendingCaptures());DecisionObservers.closeAll();}
    void occupy()throws Exception{DecisionObservers.deferCapture(()->{occupied.countDown();awaitRelease();});DecisionObservers.deferCapture(()->{occupied.countDown();awaitRelease();});assertTrue(occupied.await(2,TimeUnit.SECONDS));}
    void awaitRelease(){try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}}
    @Test void closeReleasesPolicyLeaseBeforeWaitingForReportWorkersAndDurationExcludesQueueTime()throws Exception{
        CompletableFuture<DecisionObservation> event=new CompletableFuture<>();DecisionObservers.setInternal(event::complete);occupy();
        DecisionCapture capture=DecisionCapture.begin(DecisionObservation.Platform.VELOCITY,DecisionObservation.Phase.LOGIN,"192.0.2.14",null,DecisionObservation.IdentityTrust.UNTRUSTED,System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(90));
        capture.denied(DecisionObservation.Reason.VPN_FLAG);capture.flag(DecisionObservation.Flag.VPN);
        long began=System.nanoTime();capture.close();assertTrue(System.nanoTime()-began<TimeUnit.MILLISECONDS.toNanos(100));assertEquals(0,ConnectionGuard.activePolicyDecisions());assertFalse(event.isDone());
        Thread.sleep(150);capture.denied(DecisionObservation.Reason.ACCESS_RULE);release.countDown();DecisionObservation result=event.get(2,TimeUnit.SECONDS);
        assertEquals(DecisionObservation.Reason.VPN_FLAG,result.getReason());assertTrue(result.getDurationMillis()<200);assertTrue(result.getFlags().contains(DecisionObservation.Flag.VPN));
    }
    @Test void reconfigurationDiscardsQueuedCaptureWithoutLeakingPendingCounter()throws Exception{
        DecisionObservers.setInternal(event->{});occupy();DecisionCapture capture=DecisionCapture.begin(DecisionObservation.Platform.VELOCITY,DecisionObservation.Phase.LOGIN,"192.0.2.14",null,DecisionObservation.IdentityTrust.UNTRUSTED);capture.close();assertEquals(3,DecisionObservers.pendingCaptures());DecisionObservers.setInternal(null);assertEquals(2,DecisionObservers.pendingCaptures());
    }
}
