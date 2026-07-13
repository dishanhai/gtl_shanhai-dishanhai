package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MEDiskHatchPriorityTest {

    @Test
    void slotPriorityDecreasesFromDeviceBase() {
        assertEquals(1000, MEDiskHatchPriority.forSlot(1000, 0));
        assertEquals(999, MEDiskHatchPriority.forSlot(1000, 1));
        assertEquals(998, MEDiskHatchPriority.forSlot(1000, 2));
    }

    @Test
    void slotPrioritySaturatesAtIntegerMinimum() {
        assertEquals(Integer.MIN_VALUE,
                MEDiskHatchPriority.forSlot(Integer.MIN_VALUE, 255));
    }

    @Test
    void priorityAdjustmentSaturatesAtIntegerBounds() {
        assertEquals(Integer.MAX_VALUE,
                MEDiskHatchPriority.add(Integer.MAX_VALUE - 5, 10));
        assertEquals(Integer.MIN_VALUE,
                MEDiskHatchPriority.add(Integer.MIN_VALUE + 5, -10));
        assertEquals(1000, MEDiskHatchPriority.add(0, 1000));
    }
}
