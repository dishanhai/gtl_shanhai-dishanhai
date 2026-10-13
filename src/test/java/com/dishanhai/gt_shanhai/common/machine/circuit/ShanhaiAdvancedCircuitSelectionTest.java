package com.dishanhai.gt_shanhai.common.machine.circuit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiAdvancedCircuitSelectionTest {

    @Test
    void selectAllCoversCircuitZeroThroughThirtyTwo() {
        assertEquals(33, ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT);
        assertEquals(33, Long.bitCount(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS));
        assertTrue(ShanhaiAdvancedCircuitSelection.isBitSet(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS, 0));
        assertTrue(ShanhaiAdvancedCircuitSelection.isBitSet(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS, 32));
        assertFalse(ShanhaiAdvancedCircuitSelection.isBitSet(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS, 33));
    }

    @Test
    void toggleIncludesAndRemovesCircuitZero() {
        long selected = ShanhaiAdvancedCircuitSelection.toggleBit(0L, 0);
        assertEquals(1L, selected);
        assertTrue(ShanhaiAdvancedCircuitSelection.isBitSet(selected, 0));

        long cleared = ShanhaiAdvancedCircuitSelection.toggleBit(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS, 0);
        assertEquals(32, Long.bitCount(cleared));
        assertFalse(ShanhaiAdvancedCircuitSelection.isBitSet(cleared, 0));
        assertTrue(ShanhaiAdvancedCircuitSelection.isBitSet(cleared, 32));
    }
}
