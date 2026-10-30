package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialCrossTypeParallelSourceTest {

    @Test
    void crossTypeModeSearchesAllModuleTypesWithoutChangingTheSelectionUi() throws Exception {
        String recipeLogic = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/api/machine/SelectableRecipeTypeSetRecipeLogic.java"));
        String moduleLogic = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialModuleRecipeLogic.java"));

        assertTrue(recipeLogic.contains("getLookupRecipeTypes(machine)"));
        assertTrue(moduleLogic.contains("module.getAllSelectableRecipeTypes()"));
        assertTrue(moduleLogic.contains("machine instanceof PrimordialOmegaEngineModuleBase mod && mod.isCrossTypeParallelActive()"));
    }
}
