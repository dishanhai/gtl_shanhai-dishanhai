package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：純決策測試，直接覆蓋等待、停滯、飢餓與數量上限。 */
class StellarPatternWaitPolicyTest {
    @Test void lowParallelKeepsWaitingWhileTheCurrentBatchAdvances() {
        var policy = new StellarPatternWaitPolicy(); Object batch = new Object();
        assertTrue(policy.observe(0, 200, 1, 1, 3, true, false, 0, batch));
        assertTrue(policy.observe(200, 200, 1, 1, 3, true, false, 200, batch));
        assertTrue(policy.observe(400, 200, 1, 1, 3, true, false, 400, batch));
    }

    @Test void activeFlagAloneCannotHideAStalledMachine() {
        var policy = new StellarPatternWaitPolicy(); Object batch = new Object();
        policy.observe(0, 200, 1, 1, 3, true, false, 10, batch);
        assertTrue(policy.observe(199, 200, 1, 1, 3, true, false, 10, batch));
        assertFalse(policy.observe(200, 200, 1, 1, 3, true, false, 10, batch));
        assertEquals(200, policy.progressDeadline(200), "等待本身不得延長無進展窗口");
    }

    @Test void spareCapacityAndUnknownCapacityDoNotExcuseUnconsumedInputs() {
        assertFalse(new StellarPatternWaitPolicy().observe(0, 200, 128, 1, 3, true, false, 1, new Object()));
        assertFalse(new StellarPatternWaitPolicy().observe(0, 200, -1, 1, 3, true, false, 1, new Object()));
    }

    @Test void stoppingOrExplicitFailureStillAllowsTheAlarm() {
        assertFalse(new StellarPatternWaitPolicy().observe(0, 200, 1, 1, 3, false, false, 1, new Object()));
        assertFalse(new StellarPatternWaitPolicy().observe(0, 200, 1, 1, 3, true, true, 1, new Object()));
    }

    @Test void batchChangeCountsAsProgressButRepeatedOmissionEventuallyStopsDeferring() {
        var policy = new StellarPatternWaitPolicy();
        assertTrue(policy.observe(0, 200, 1, 1, 1, true, false, 50, new Object()));
        assertTrue(policy.observe(100, 200, 1, 1, 1, true, false, 0, new Object()));
        assertFalse(policy.observe(200, 200, 1, 1, 100, true, false, 0, new Object()),
                "後續補料不能把同槽等待機會無限拉長");
    }

    @Test void oneHostsProgressCannotAdvanceAnotherHostsWatch() {
        var first = new StellarPatternWaitPolicy(); var second = new StellarPatternWaitPolicy();
        Object a = new Object(), b = new Object();
        first.observe(0, 200, 1, 1, 2, true, false, 1, a);
        second.observe(0, 200, 1, 1, 2, true, false, 1, b);
        assertTrue(first.observe(200, 200, 1, 1, 2, true, false, 100, a));
        assertFalse(second.observe(200, 200, 1, 1, 2, true, false, 1, b));
    }

    @Test void threadMultipliersAndOverflowPreserveTheActualCapacity() {
        assertEquals(128, StellarPatternWaitPolicy.multiply(1, 128));
        assertEquals(Long.MAX_VALUE, StellarPatternWaitPolicy.multiply(Long.MAX_VALUE, 128));
        assertEquals(-1, StellarPatternWaitPolicy.multiply(0, 128));
        assertEquals(Long.MAX_VALUE, StellarPatternWaitPolicy.add(Long.MAX_VALUE, 1));
        assertFalse(new StellarPatternWaitPolicy().observe(0, 200, Long.MAX_VALUE,
                Long.MAX_VALUE - 1, 1, true, false, 1, new Object()));
        assertTrue(new StellarPatternWaitPolicy().observe(0, 200, Long.MAX_VALUE,
                Long.MAX_VALUE, 1, true, false, 1, new Object()));
    }

    @Test void samplingTheSameProgressAfterSeveralShortBatchesStillRecognizesSuccessfulWork() {
        var policy = new StellarPatternWaitPolicy(); Object batch = new Object();
        policy.observe(0, 200, 1, 1, 100, true, false, 5, 5L, 10, batch);
        assertTrue(policy.observe(200, 200, 1, 1, 100, true, false, 5, 205L, 10, batch));
        assertFalse(policy.observe(400, 200, 1, 1, 100, true, false, 5, 205L, 10, batch));
    }

    @Test void shortReusedBatchesCannotHideAnOmittedSlotForever() {
        var policy = new StellarPatternWaitPolicy(); Object batch = new Object();
        policy.observe(0, 200, 1, 1, 1, true, false, 5, 5L, 10, batch);
        assertFalse(policy.observe(20, 200, 1, 1, 1, true, false, 5, 25L, 10, batch));
    }
}
