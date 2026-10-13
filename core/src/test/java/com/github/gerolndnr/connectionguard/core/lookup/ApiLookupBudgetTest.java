package com.github.gerolndnr.connectionguard.core.lookup;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ApiLookupBudgetTest {
    @Test void concurrentDistinctReservationsNeverExceedTheGlobalCap() throws Exception {
        ApiLookupBudget budget = new ApiLookupBudget(); budget.configure(8); ProviderHealth source = new ProviderHealth();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<FailureReason>> futures = new ArrayList<>();
            for (int i=0;i<100;i++) futures.add(workers.submit(() -> budget.reserve(source)));
            int allowed=0;
            for (Future<FailureReason> future:futures) if(future.get(2,TimeUnit.SECONDS)==FailureReason.NONE) allowed++;
            assertEquals(8,allowed); assertEquals(8,budget.snapshot().reserved); assertEquals(92,budget.snapshot().denied);
            assertEquals(8,source.snapshot().dailyUsed);
        } finally { workers.shutdownNow(); }
    }
    @Test void sourceQuotaAndOpenCircuitDoNotConsumeAnotherGlobalReservation() {
        ApiLookupBudget budget=new ApiLookupBudget(); budget.configure(5);
        ProviderHealth quota=new ProviderHealth(); quota.budgets(1,0);
        assertEquals(FailureReason.NONE,budget.reserve(quota));
        assertEquals(FailureReason.BUDGET_EXHAUSTED,budget.reserve(quota));
        ProviderHealth circuit=new ProviderHealth();
        circuit.record(FailureReason.TIMEOUT,null,LookupSettings.defaults());
        assertEquals(FailureReason.CIRCUIT_OPEN,budget.reserve(circuit));
        assertEquals(1,budget.snapshot().reserved);
    }
    @Test void loweringDisablingAndRestoringTheLimitNeverReplenishesTheDay() {
        ApiLookupBudget budget=new ApiLookupBudget(); ProviderHealth source=new ProviderHealth();
        budget.configure(2); assertEquals(FailureReason.NONE,budget.reserve(source));
        budget.configure(1); assertEquals(FailureReason.BUDGET_EXHAUSTED,budget.reserve(source));
        budget.configure(0); assertEquals(FailureReason.NONE,budget.reserve(source));
        budget.configure(2); assertEquals(FailureReason.BUDGET_EXHAUSTED,budget.reserve(source));
        assertEquals(2,budget.snapshot().reserved);
    }
    @Test void utcRolloverResetsOnceAndClockRollbackCannotReplenish() {
        AtomicLong clock=new AtomicLong(Instant.parse("2026-10-12T23:59:59Z").toEpochMilli());
        ApiLookupBudget budget=new ApiLookupBudget(clock::get); budget.configure(1); ProviderHealth source=new ProviderHealth();
        assertEquals(FailureReason.NONE,budget.reserve(source));
        assertTrue(budget.claimAlert()); assertFalse(budget.claimAlert());
        clock.addAndGet(2000); assertEquals(FailureReason.NONE,budget.reserve(source));
        clock.addAndGet(-2000); assertEquals(FailureReason.BUDGET_EXHAUSTED,budget.reserve(source));
        assertEquals(1,budget.snapshot().reserved); assertEquals("2026-10-13",budget.snapshot().day.toString());
    }
}
