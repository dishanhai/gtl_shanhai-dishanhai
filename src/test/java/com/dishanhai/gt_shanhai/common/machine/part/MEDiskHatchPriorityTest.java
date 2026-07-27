package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MEDiskHatchPriorityTest {

    @Test
    void allSlotsShareTheDevicePriorityBucket() {
        // 槽位不再按 basePriority - slot 递减：每槽独立优先级会把 AE2 NetworkStorage 的
        // TreeMap 撑成上百个桶，全网每次 insert/extract 的固定开销 ×20-100（外层循环无
        // 提前退出）。槽位顺序由挂载插入顺序表达（同桶内 List 顺序），语义不变。
        assertEquals(1000, MEDiskHatchPriority.forSlot(1000, 0));
        assertEquals(1000, MEDiskHatchPriority.forSlot(1000, 1));
        assertEquals(1000, MEDiskHatchPriority.forSlot(1000, 255));
        assertEquals(Integer.MIN_VALUE, MEDiskHatchPriority.forSlot(Integer.MIN_VALUE, 255));
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
