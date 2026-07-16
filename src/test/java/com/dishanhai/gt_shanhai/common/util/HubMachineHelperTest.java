package com.dishanhai.gt_shanhai.common.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubMachineHelperTest {

    @Test
    void forcesOutputChanceBeforeParallelChanceIsRolled() throws Exception {
        String helper = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/util/HubMachineHelper.java"));
        String mixin = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/mixin/ParallelOutputChanceBypassMixin.java"));
        String mixinConfig = Files.readString(Path.of("src/main/resources/gt_shanhai.mixin.json"));

        assertTrue(mixin.contains("public static GTRecipe getRecipeOutputChance("));
        assertTrue(mixin.contains("HubMachineHelper.hasChanceBypass(controller)"));
        assertTrue(mixin.contains("HubMachineHelper.forceFullOutputChance(recipe)"));
        assertFalse(mixin.contains("@ModifyVariable"));
        assertTrue(mixinConfig.contains("\"ParallelOutputChanceBypassMixin\""));
        assertTrue(helper.contains("GTRecipe copy = recipe.copy();"));
        assertTrue(helper.contains("forceFullChance(copy.outputs);"));
        assertTrue(helper.contains("forceFullChance(copy.tickOutputs);"));
        assertTrue(helper.contains("content.chance = content.maxChance;"));
    }
}
