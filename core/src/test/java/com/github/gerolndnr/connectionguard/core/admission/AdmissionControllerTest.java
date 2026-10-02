package com.github.gerolndnr.connectionguard.core.admission;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionControllerTest {
    private final AtomicLong now = new AtomicLong();
    private final Map<String, Object> values = new HashMap<>();
    private AdmissionController controller() {
        values.put("overload.enabled", true);
        values.putIfAbsent("overload.window-ms", 1000);
        values.putIfAbsent("overload.cooldown-ms", 2000);
        return new AdmissionController(new AdmissionSettings(values::get), now::get);
    }
    private LoginAdmission attempt(AdmissionController controller, String ip) { return controller.admit(ip, true, false); }
    private void advance(long millis) { now.addAndGet(millis * 1000000L); }
    @Test void defaultsAndFullyExemptLoginsHaveNoLimitsOrRecords() {
        AdmissionController disabled = new AdmissionController(AdmissionSettings.defaults(), now::get);
        for (int i = 0; i < 100; i++) assertTrue(attempt(disabled, "192.0.2.1").isAllowed());
        assertEquals(0, disabled.tracked());
        values.put("overload.per-ip-attempts", 1);
        AdmissionController active = controller();
        for (int i = 0; i < 100; i++) assertTrue(active.admit("192.0.2.1", false, false).isAllowed());
        assertTrue(attempt(active, "192.0.2.1").isAllowed());
        assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "192.0.2.1").getReason());
    }
    @Test void oneLimitedIpDoesNotChargeGlobalOrExtendItsCooldown() {
        values.put("overload.per-ip-attempts", 1); values.put("overload.global-attempts", 2);
        AdmissionController active = controller();
        assertTrue(attempt(active, "192.0.2.1").isAllowed());
        for (int i = 0; i < 100; i++) assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "192.0.2.1").getReason());
        assertTrue(active.describe().contains("state=NORMAL"));
        assertTrue(attempt(active, "198.51.100.1").isAllowed());
        advance(1999);
        assertEquals(1, attempt(active, "192.0.2.1").getRetryMillis());
        advance(1);
        assertTrue(attempt(active, "192.0.2.1").isAllowed());
    }
    @Test void subnetLimitsDoNotAffectOtherNetworksAndMappedIpv6SharesIpv4() {
        values.put("overload.per-subnet-attempts", 2);
        AdmissionController active = controller();
        assertTrue(attempt(active, "192.0.2.1").isAllowed());
        assertTrue(attempt(active, "::ffff:192.0.2.2").isAllowed());
        assertEquals(LoginAdmission.Reason.SUBNET_LIMIT, attempt(active, "192.0.2.3").getReason());
        assertTrue(attempt(active, "192.0.3.1").isAllowed());
        assertTrue(attempt(active, "2001:db8:1::1").isAllowed());
        assertTrue(attempt(active, "2001:db8:1::2").isAllowed());
        assertEquals(LoginAdmission.Reason.SUBNET_LIMIT, attempt(active, "2001:db8:1::3").getReason());
        assertTrue(attempt(active, "2001:db8:2::1").isAllowed());
    }
    @Test void mappedIpv6AlsoSharesTheSingleIpCounter() {
        values.put("overload.per-ip-attempts", 1);
        AdmissionController active = controller();
        assertTrue(attempt(active, "192.0.2.1").isAllowed());
        assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "::ffff:c000:201").getReason());
        assertEquals(1, active.tracked());
    }
    @Test void globalPauseRecoversEvenWhenCooldownIsShorterThanWindow() {
        values.put("overload.global-attempts", 2); values.put("overload.cooldown-ms", 100);
        AdmissionController active = controller();
        assertTrue(attempt(active, "192.0.2.1").isAllowed()); assertTrue(attempt(active, "192.0.2.2").isAllowed());
        assertEquals(LoginAdmission.Reason.GLOBAL_LIMIT, attempt(active, "192.0.2.3").getReason());
        assertTrue(active.describe().contains("state=GLOBAL_PAUSE"));
        advance(99); assertEquals(1, attempt(active, "192.0.2.4").getRetryMillis());
        advance(1); assertTrue(active.describe().contains("state=NORMAL"));
        assertTrue(attempt(active, "192.0.2.5").isAllowed()); assertTrue(attempt(active, "192.0.2.6").isAllowed());
        assertEquals(LoginAdmission.Reason.GLOBAL_LIMIT, attempt(active, "192.0.2.7").getReason());
    }
    @Test void churnCannotEvictLiveRecordsAndCapacityRecovers() {
        values.put("overload.per-ip-attempts", 1); values.put("overload.tracking-capacity", 16);
        AdmissionController active = controller();
        for (int i = 1; i <= 16; i++) assertTrue(attempt(active, "192.0.2." + i).isAllowed());
        for (int i = 17; i <= 200; i++) assertEquals(LoginAdmission.Reason.TRACKING_CAPACITY, attempt(active, "192.0.2." + i).getReason());
        assertEquals(16, active.tracked());
        assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "192.0.2.1").getReason());
        advance(1000);
        assertTrue(attempt(active, "198.51.100.1").isAllowed());
        assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "192.0.2.1").getReason());
        assertEquals(2, active.tracked());
        advance(1000); assertTrue(attempt(active, "192.0.2.1").isAllowed());
    }
    @Test void capacityRetryUsesActualBlockedExpiryNotTheShorterWindow() {
        values.put("overload.per-ip-attempts", 1); values.put("overload.tracking-capacity", 16);
        AdmissionController active = controller();
        for (int i = 1; i <= 16; i++) {
            assertTrue(attempt(active, "192.0.2." + i).isAllowed());
            assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "192.0.2." + i).getReason());
        }
        assertEquals(2000, attempt(active, "198.51.100.1").getRetryMillis());
        advance(1999); assertEquals(1, attempt(active, "198.51.100.1").getRetryMillis());
        advance(1); assertTrue(attempt(active, "198.51.100.1").isAllowed());
    }
    @Test void ipAndSubnetRecordsShareOneBoundedCapacity() {
        values.put("overload.per-ip-attempts", 10); values.put("overload.per-subnet-attempts", 10); values.put("overload.tracking-capacity", 16);
        AdmissionController active = controller();
        for (int i = 0; i < 8; i++) assertTrue(attempt(active, "192.0." + i + ".1").isAllowed());
        assertEquals(16, active.tracked());
        assertEquals(LoginAdmission.Reason.TRACKING_CAPACITY, attempt(active, "198.51.100.1").getReason());
        assertTrue(active.describe().contains("state=NORMAL"));
    }
    @Test void denialIsExplicitAndObservationSuppressesItWithoutAllowingLookup() {
        values.put("overload.per-ip-attempts", 1);
        AdmissionController permit = controller(); attempt(permit, "192.0.2.1");
        assertFalse(attempt(permit, "192.0.2.1").shouldDeny());
        values.put("overload.deny-connections", true);
        AdmissionController deny = controller(); attempt(deny, "192.0.2.1");
        assertTrue(attempt(deny, "192.0.2.1").shouldDeny());
        LoginAdmission observe = deny.admit("192.0.2.1", true, true);
        assertFalse(observe.isAllowed()); assertFalse(observe.shouldDeny());
    }
    @Test void warningsAreBoundedAndNeverContainIdentity() {
        values.put("overload.per-ip-attempts", 1); values.put("overload.alarm-cooldown-ms", 1000);
        AdmissionController active = controller(); attempt(active, "192.0.2.1");
        assertTrue(attempt(active, "192.0.2.1").shouldAlert());
        for (int i = 0; i < 100; i++) assertFalse(attempt(active, "192.0.2.1").shouldAlert());
        advance(999); assertFalse(attempt(active, "192.0.2.1").shouldAlert());
        advance(1); assertTrue(attempt(active, "192.0.2.1").shouldAlert());
        assertFalse(active.describe().contains("192.0.2.1"));
    }
    @Test void concurrentCallersCannotOverspendTheGlobalLimit() throws Exception {
        values.put("overload.global-attempts", 5);
        AdmissionController active = controller();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 100; i++) jobs.add(workers.submit(() -> attempt(active, "192.0.2.1").isAllowed()));
            int allowed = 0;
            for (Future<Boolean> job : jobs) if (job.get(2, TimeUnit.SECONDS)) allowed++;
            assertEquals(5, allowed); assertEquals(0, active.tracked());
        } finally { workers.shutdownNow(); }
    }
    @Test void monotonicNanoTimeWrapDoesNotProlongCooldown() {
        values.put("overload.global-attempts", 1);
        now.set(Long.MAX_VALUE - 1000000000L);
        AdmissionController active = controller(); attempt(active, "192.0.2.1");
        assertEquals(LoginAdmission.Reason.GLOBAL_LIMIT, attempt(active, "192.0.2.1").getReason());
        advance(2000); assertTrue(attempt(active, "192.0.2.1").isAllowed());
    }
    @Test void invalidDraftsAreRejectedBeforeChangingTheActiveController() {
        values.put("overload.per-ip-attempts", 1);
        AdmissionController active = controller(); attempt(active, "192.0.2.1");
        values.put("overload.window-ms", 1);
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(values::get, Collections.emptyList()));
        assertEquals(LoginAdmission.Reason.IP_LIMIT, attempt(active, "192.0.2.1").getReason());
        values.remove("overload.window-ms"); values.put("overload.per-ip-attempts", -1);
        assertThrows(IllegalArgumentException.class, () -> controller());
        values.put("overload.per-ip-attempts", 0);
        assertThrows(IllegalArgumentException.class, () -> controller());
        values.put("overload.per-ip-attempts", 1); values.put("overload.ipv6-prefix", 129);
        assertThrows(IllegalArgumentException.class, () -> controller());
        values.remove("overload.ipv6-prefix"); values.put("overload.tracking-capacity", 16385);
        assertThrows(IllegalArgumentException.class, () -> controller());
        values.remove("overload.tracking-capacity"); values.put("overload.global-attempts", 1.5);
        assertThrows(IllegalArgumentException.class, () -> controller());
    }
}
