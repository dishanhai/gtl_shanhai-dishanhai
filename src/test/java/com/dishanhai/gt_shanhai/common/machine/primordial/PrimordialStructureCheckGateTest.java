package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PrimordialStructureCheckGateTest {
    @Test
    void pendingFormationPreventsRepeatedProbesUntilCompletion() {
        PrimordialStructureCheckGate gate = new PrimordialStructureCheckGate();
        Object first = gate.tryStart();
        assertNotNull(first);
        for (int retry = 0; retry < 100; retry++) assertNull(gate.tryStart());
        assertTrue(gate.isCurrent(first));
        gate.finish(first);
        assertNotNull(gate.tryStart());
    }

    @Test
    void cancelledCompletionCannotClearNewLifetimeTask() {
        PrimordialStructureCheckGate gate = new PrimordialStructureCheckGate();
        Object old = gate.tryStart();
        gate.cancel();
        Object current = gate.tryStart();
        assertFalse(gate.isCurrent(old));
        gate.finish(old);
        assertTrue(gate.isCurrent(current));
        assertNull(gate.tryStart());
        gate.finish(current);
        assertNotNull(gate.tryStart());
    }

    @Test
    void concurrentSearchesHaveOnlyOneWinner() throws Exception {
        PrimordialStructureCheckGate gate = new PrimordialStructureCheckGate();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return gate.tryStart();
                }));
            }
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get() != null) winners++;
            assertEquals(1, winners);
        }
    }
}
