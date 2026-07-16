package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeExecutionGuardTest {

    @Test
    void identifiesDummyRecipeAsAuxiliaryMachineInput() {
        assertTrue(PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(
                new ResourceLocation("gtceu", "dummy")));
    }

    @Test
    void keepsNormalRecipesUnderPatternSlotRules() {
        assertFalse(PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(
                new ResourceLocation("gtceu", "antientropy_condensation")));
        assertFalse(PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(null));
    }
}
