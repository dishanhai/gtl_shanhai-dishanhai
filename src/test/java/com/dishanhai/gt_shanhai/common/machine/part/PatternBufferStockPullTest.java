package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternBufferStockPullTest {

    @Test
    void requestUsesBatchAndStopsAtTheMaintainAmount() {
        assertEquals(0L, PatternBufferRequestMath.toRequest(20L, 20L, 10L, true, true));
        assertEquals(1L, PatternBufferRequestMath.toRequest(19L, 20L, 10L, true, true));
        assertEquals(30L, PatternBufferRequestMath.toRequest(0L, 100L, 30L, true, true));
        assertEquals(0L, PatternBufferRequestMath.toRequest(0L, 100L, 30L, false, true));
        assertEquals(0L, PatternBufferRequestMath.toRequest(0L, 100L, 30L, true, false));
        assertEquals(0L, PatternBufferRequestMath.toRequest(0L, 0L, 30L, true, true));
        assertEquals(0L, PatternBufferRequestMath.toRequest(0L, 100L, 0L, true, true));
    }

    @Test
    void tagPullKeepsManualSlotsAndRefillsPreviousAutomaticSlots() {
        List<String> assigned = PatternBufferTagSlots.assign(
                Arrays.asList("manual", null, "kept"),
                Arrays.asList(null, "tag-a", null),
                Arrays.asList("tag-a", "tag-b", "manual"));

        assertEquals(Arrays.asList(null, "tag-a", null), assigned);
    }

    @Test
    void tagPullFillsEmptySlotsInOrderAndStopsAtCapacity() {
        List<String> assigned = PatternBufferTagSlots.assign(
                Arrays.asList(null, null),
                Arrays.asList(null, null),
                Arrays.asList("a", "b", "c"));

        assertEquals(Arrays.asList("a", "b"), assigned);
    }
}
