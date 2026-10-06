package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiRecipeFluidQuerySourceTest {

    @Test
    void fluidQueriesKeepInputAndOutputIndexesSeparate() throws Exception {
        String index = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeReverseIndex.java"));
        String query = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java"));

        assertTrue(index.contains("FLUID_INPUTS"));
        assertTrue(index.contains("FLUID_OUTPUTS"));
        assertTrue(index.contains("queryFluidInput"));
        assertTrue(index.contains("queryFluidOutput"));
        assertTrue(query.contains("byInputFluid"));
        assertTrue(query.contains("byOutputFluid"));
    }
}
