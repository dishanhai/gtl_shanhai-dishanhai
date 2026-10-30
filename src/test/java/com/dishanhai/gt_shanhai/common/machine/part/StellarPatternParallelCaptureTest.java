package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：成功扣料的觀測必須限於對應主機和目前配方批次。 */
class StellarPatternParallelCaptureTest {
    private static final class State implements StellarPatternParallelState {
        private long value = -1;
        public long gtShanhai$getObservedAllocatedParallel() { return value; }
        public void gtShanhai$recordAllocatedParallel(long amount) { value = amount; }
    }

    @Test void countsOnlySuccessfulInputsForTheCurrentHost() {
        Object host = new Object(), other = new Object(); State state = new State();
        try (var scope = StellarPatternParallelCapture.begin(host, state)) {
            StellarPatternParallelCapture.record(host, 3, true);
            StellarPatternParallelCapture.record(host, 100, false);
            StellarPatternParallelCapture.record(other, 200, true);
            StellarPatternParallelCapture.record(host, 7, true);
            assertEquals(10, state.value);
        }
        StellarPatternParallelCapture.record(host, 100, true);
        assertEquals(10, state.value);
    }

    @Test void nestedHostsAndExceptionsRestoreThePreviousScope() {
        Object a = new Object(), b = new Object(); State first = new State(), second = new State();
        try (var outer = StellarPatternParallelCapture.begin(a, first)) {
            StellarPatternParallelCapture.record(a, 2, true);
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = StellarPatternParallelCapture.begin(b, second)) {
                    StellarPatternParallelCapture.record(a, 9, true);
                    StellarPatternParallelCapture.record(b, 5, true);
                    throw new IllegalStateException();
                }
            });
            StellarPatternParallelCapture.record(a, 3, true);
        }
        assertEquals(5, first.value);
        assertEquals(5, second.value);
        assertFalse(StellarPatternParallelCapture.isObserving(a));
    }

    @Test void totalsSaturateAndAreThreadLocal() throws Exception {
        Object host = new Object(); State state = new State(); AtomicReference<Boolean> otherThread = new AtomicReference<>();
        try (var scope = StellarPatternParallelCapture.begin(host, state)) {
            Thread thread = new Thread(() -> otherThread.set(StellarPatternParallelCapture.isObserving(host)));
            thread.start(); thread.join();
            StellarPatternParallelCapture.record(host, Long.MAX_VALUE, true);
            StellarPatternParallelCapture.record(host, 2, true);
            assertEquals(Long.MAX_VALUE, state.value);
        }
        assertEquals(Boolean.FALSE, otherThread.get());
    }
}
