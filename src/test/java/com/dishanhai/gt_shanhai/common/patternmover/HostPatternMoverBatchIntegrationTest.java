package com.dishanhai.gt_shanhai.common.patternmover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostPatternMoverBatchIntegrationTest {
    @Test
    void batchesNestedWritesAndEndsOnException() {
        int[] rebuilds = {0};
        HostPatternMoverBatch.Rebuilder target = () -> { rebuilds[0]++; return true; };
        assertThrows(IllegalStateException.class, () -> HostPatternMoverBatch.run(() -> {
            assertTrue(HostPatternMoverBatch.suppress(target));
            HostPatternMoverBatch.run(() -> {
                assertTrue(HostPatternMoverBatch.suppress(target));
                return null;
            });
            assertEquals(0, rebuilds[0]);
            throw new IllegalStateException("expected");
        }));
        assertEquals(1, rebuilds[0]);
        assertFalse(HostPatternMoverBatch.active());
    }
}
