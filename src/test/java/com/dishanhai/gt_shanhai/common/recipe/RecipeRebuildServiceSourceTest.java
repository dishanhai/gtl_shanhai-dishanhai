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
        assertTrue(source.contains("if (notify && reason != RebuildReason.EDITOR_COMMIT)"));
        // 批次重建只在收尾發一次同步包；逐 type 發包會造成封包風暴
        assertTrue(source.contains("notifyTypes"));
        assertTrue(source.indexOf("if (!typeIds.isEmpty()) notifyTypes(typeIds)") > 0);
        assertTrue(source.indexOf("captureManagerOriginals") < source.indexOf("captureLookupOriginals"));
        assertTrue(source.contains("withoutSuperseded"));
        assertTrue(source.contains("refreshLookupEntries"));
        assertTrue(source.contains("unlinkRecipes"));
        assertTrue(source.contains("replaceEntries"));
    }

    @Test
    void editorCommitRefreshesOnlyTheEditedLookupEntries() throws Exception {
        String editor = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOps.java"));
        assertTrue(editor.contains("RecipeRebuildService.refreshLookupEntries"));
        assertTrue(!editor.contains("RebuildReason.EDITOR_COMMIT"));
        assertTrue(!editor.contains("rebuildVanillaManager"));
    }
}
