package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelfSynthesisPlanTest {

    @Test
    void oneCraftWhenPushedAmountMatchesPattern() {
        assertEquals(1L, SelfSynthesisPlan.craftTimes(new long[] {4L, 1000L}, new long[] {4L, 1000L}));
    }

    @Test
    void bulkPushScalesToTheSmallestInput() {
        assertEquals(3L, SelfSynthesisPlan.craftTimes(new long[] {2L, 5L}, new long[] {8L, 15L}));
        assertEquals(6L, SelfSynthesisPlan.scaledAmount(2L, 3L));
    }

    @Test
    void shortPushDoesNotCraft() {
        assertEquals(0L, SelfSynthesisPlan.craftTimes(new long[] {4L}, new long[] {3L}));
    }

    @Test
    void patternWithoutInputsStillCompletesOneCraft() {
        assertEquals(1L, SelfSynthesisPlan.craftTimes(new long[0], new long[0]));
    }

    @Test
    void uploadMappingKeepsSentinelAndAddsPatternType() {
        List<String> mapped = SelfSynthesisUpload.mapRecipeTypes(
                List.of("gtceu:assembler"), "gtceu:circuit_assembler");
        assertEquals(List.of(
                SelfSynthesisUpload.RECIPE_TYPE_ID,
                "gtceu:assembler",
                "gtceu:circuit_assembler"), mapped);
        assertEquals(50, SelfSynthesisUpload.searchBonus(mapped, "多方块"));
        assertEquals(0, SelfSynthesisUpload.searchBonus(mapped, "gtceu:assembler"));
        assertEquals(0, SelfSynthesisUpload.searchBonus(List.of("gtceu:assembler"), "多方块"));
    }
}
