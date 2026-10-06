package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiRecipeIoCapabilityPreservationSourceTest {

    @Test
    void recipeSnapshotAndApplyPreserveRegisteredNonVisualCapabilities() throws Exception {
        String io = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeIoApply.java"));
        String validation = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorValidation.java"));

        assertTrue(io.contains("GTRegistries.RECIPE_CAPABILITIES.getKey(entry.getKey())"));
        assertTrue(io.contains("table.remove(ItemRecipeCapability.CAP)"));
        assertTrue(!io.contains("table.clear()"));
        assertTrue(io.contains("GTRegistries.RECIPE_CAPABILITIES.get(key)"));
        assertTrue(validation.contains("GTRegistries.RECIPE_CAPABILITIES.get(key)"));
    }
}
