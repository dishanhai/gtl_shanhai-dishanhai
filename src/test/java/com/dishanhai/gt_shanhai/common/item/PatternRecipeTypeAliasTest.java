package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeTypeAliasTest {

    @Test
    void vanillaSmeltingCanonicalizesToGtceuElectricFurnace() {
        assertEquals("gtceu:electric_furnace",
                PatternRecipeTypeHelper.canonicalRecipeTypeId("minecraft:smelting"));
        assertEquals("gtceu:electric_furnace",
                PatternRecipeTypeHelper.canonicalRecipeTypeId(" smelting "));
        assertEquals("gtceu:electric_furnace",
                PatternRecipeTypeHelper.canonicalRecipeTypeId("GTCEU:ELECTRIC_FURNACE"));
    }

    @Test
    void vanillaSmeltingAndGtceuElectricFurnaceAreEquivalent() {
        assertTrue(PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                "minecraft:smelting", "gtceu:electric_furnace"));
        assertTrue(PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                "smelting", "gtceu:electric_furnace"));
        assertFalse(PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                "minecraft:smelting", "gtceu:alloy_smelter"));
    }
}
