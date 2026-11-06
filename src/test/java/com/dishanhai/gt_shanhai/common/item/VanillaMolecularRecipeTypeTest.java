package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：實際執行共用類型判定，確保分子專用分流與其他快速上傳互相隔離。 */
class VanillaMolecularRecipeTypeTest {

    @Test
    void smithingAndStonecuttingUseTheNativeMolecularRoute() {
        assertTrue(PatternRecipeTypeHelper.isVanillaMolecularRecipeType("minecraft:smithing"));
        assertTrue(PatternRecipeTypeHelper.isVanillaMolecularRecipeType("minecraft:stonecutting"));
    }

    @Test
    void otherRecipesAndMissingMetadataDoNotEnterTheNewRoute() {
        for (String id : new String[] { null, "", "minecraft:crafting", "minecraft:smelting",
                "gtceu:assembler", "other:smithing", "other:stonecutting" }) {
            assertFalse(PatternRecipeTypeHelper.isVanillaMolecularRecipeType(id), String.valueOf(id));
        }
        assertFalse(PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                "minecraft:smithing", "minecraft:stonecutting"));
    }
}
