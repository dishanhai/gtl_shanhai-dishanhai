package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRebuildServiceSourceTest {

    @Test
    void rebuildStartsFromOriginalsAndAppliesRulesOnce() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java"));

        assertTrue(source.contains("RecipeOriginalSnapshotStore.copiesOf"));
        assertTrue(source.indexOf("applyStripByType") < source.indexOf("applyReplaceByType"));
        assertTrue(source.indexOf("applyReplaceByType") < source.indexOf("isDeletedByRuntimeRule"));
        assertTrue(source.contains("lookup.removeAllRecipes()"));
        assertTrue(source.contains("lookup.addRecipe"));
        assertTrue(source.contains("runPatternCacheInvalidationBatch"));
        assertTrue(source.contains("if (reason != RebuildReason.EDITOR_COMMIT)"));
        assertTrue(source.contains("RecipeSyncPacket.syncToAll(java.util.Set.of(recipeTypeId))"));
    }
}
