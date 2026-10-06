package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeOriginalSnapshotStoreTest {

    @Test
    void snapshotStoreDefinesStableCopyAndClearContract() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStore.java"));

        assertTrue(source.contains("capture(String recipeTypeId, GTRecipe recipe)"));
        assertTrue(source.contains("copyOf(String recipeTypeId, String recipeId)"));
        assertTrue(source.contains("copiesOf(String recipeTypeId)"));
        assertTrue(source.contains("ids(String recipeTypeId)"));
        assertTrue(source.contains("clear(String recipeTypeId)"));
        assertTrue(source.contains("clearAll()"));
        assertTrue(source.contains("recipe.copy()"));
    }

    @Test
    void apiNoLongerOwnsTheCanonicalOriginalMap() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java"));

        assertTrue(!source.contains("private static final Map<String, List<GTRecipe>> RECIPE_ORIGINALS"),
                "DShanhaiRecipeModifierAPI must delegate canonical snapshots to the store");
    }
}
