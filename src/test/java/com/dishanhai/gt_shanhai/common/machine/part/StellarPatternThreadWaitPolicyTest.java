package com.dishanhai.gt_shanhai.common.machine.part;

// Author: dishanhai

import com.dishanhai.gt_shanhai.api.machine.RecipeThreadSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StellarPatternThreadWaitPolicyTest {
    private static RecipeThreadSnapshot state(long capacity, int active, long ticks, long completed) {
        return new RecipeThreadSnapshot(capacity, active, active, true, false,
                Long.MAX_VALUE, active, ticks, completed, capacity > 0L && active >= capacity);
    }

    @Test void twoRunningThreadsAndTwoWaitingRecipesDeferEvenWithUnlimitedParallel() {
        var policy = new StellarPatternThreadWaitPolicy();
        assertTrue(policy.observe(0, 200, state(2, 2, 10, 0), 2, false));
        assertTrue(policy.observe(200, 200, state(2, 2, 410, 0), 2, false));
        assertTrue(policy.observe(400, 200, state(2, 2, 810, 0), 2, false));
        assertEquals(600, policy.progressDeadline(200));
    }

    @Test void queueLengthDoesNotPretendThatSpareThreadsAreOccupied() {
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200, state(4, 2, 1, 0), 20, false));
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200, state(-1, 2, 1, 0), 2, false));
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200, state(2, 2, 1, 0), 0, false));
    }

    @Test void activeThreadsWithoutActualProgressStillTimeOut() {
        var policy = new StellarPatternThreadWaitPolicy();
        assertTrue(policy.observe(0, 200, state(2, 2, 10, 0), 2, false));
        assertTrue(policy.observe(199, 200, state(2, 2, 10, 0), 2, false));
        assertFalse(policy.observe(200, 200, state(2, 2, 10, 0), 2, false));
        assertEquals(200, policy.progressDeadline(200));
    }

    @Test void pauseDrainAndExplicitFailureCannotBeExcusedByOtherWork() {
        var full = state(2, 2, 1, 0);
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200, full, 2, true));
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200,
                new RecipeThreadSnapshot(2, 2, 2, false, false, 100, 2, 1, 0, true), 2, false));
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200,
                new RecipeThreadSnapshot(2, 2, 2, true, true, 100, 2, 1, 0, true), 2, false));
        assertFalse(new StellarPatternThreadWaitPolicy().observe(0, 200,
                new RecipeThreadSnapshot(2, 2, 0, true, false, 100, 2, 1, 0, true), 2, false));
    }

    @Test void repeatedOmissionIsBoundedByReleasedBatchesEvenIfAnotherLongBatchAdvances() {
        var policy = new StellarPatternThreadWaitPolicy();
        assertTrue(policy.observe(0, 200, state(2, 2, 1, 10), 2, false));
        assertTrue(policy.observe(20, 200, state(2, 2, 40, 11), 100, false));
        assertTrue(policy.observe(40, 200, state(2, 2, 80, 13), 100, false));
        assertFalse(policy.observe(60, 200, state(2, 2, 120, 14), 100, false),
                "後續補料不能擴大既定等待機會；其他執行緒的進展不能永久掩蓋飢餓");
    }

    @Test void parallelReservationsStillDeferWhenThereAreSpareRecipeThreads() {
        assertTrue(new StellarPatternThreadWaitPolicy().observe(0, 200,
                new RecipeThreadSnapshot(4, 2, 2, true, false, 100, 100, 10, 0, true), 2, false));
    }

    @Test void downgradeReservationsCanBlockAdmissionWithoutFillingEitherCapacity() {
        var state = new RecipeThreadSnapshot(4, 3, 3, true, false, 90, 75, 10, 0, true);
        assertFalse(state.threadsFull()); assertFalse(state.parallelFull());
        assertTrue(new StellarPatternThreadWaitPolicy().observe(0, 200, state, 2, false));
    }

    @Test void separateHostsCannotShareProgressAndCounterResetDoesNotCountAsWork() {
        var a = new StellarPatternThreadWaitPolicy(); var b = new StellarPatternThreadWaitPolicy();
        a.observe(0, 200, state(2, 2, 100, 4), 2, false);
        b.observe(0, 200, state(2, 2, 100, 4), 2, false);
        assertTrue(a.observe(200, 200, state(2, 2, 200, 4), 2, false));
        assertFalse(b.observe(200, 200, state(2, 2, 1, 0), 2, false));
    }

    @Test void historicalCompletionsBeforeTheSlotQueuedDoNotSpendItsWaitBudget() {
        var policy = new StellarPatternThreadWaitPolicy();
        assertTrue(policy.observe(0, 200, state(2, 2, 999, 999), 2, false));
        assertTrue(policy.observe(20, 200, state(2, 2, 1000, 1000), 2, false));
    }
}
