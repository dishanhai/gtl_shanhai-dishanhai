package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorSyncSourceTest {

    @Test
    void restoreAndDeletePathsInvalidateLiveTablesAndReverseIndexes() throws Exception {
        String rebuild = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java"));
        String table = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeTable.java"));
        String ops = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOps.java"));
        String factory = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/DShanhaiRecipeEditorFactory.java"));

        assertTrue(rebuild.contains("ShanhaiRecipeReverseIndex.invalidate"));
        assertTrue(rebuild.contains("RecipeSyncPacket.syncToAll"));
        assertTrue(table.contains("ShanhaiRecipeTableHook.invalidateCaches"));
        assertTrue(ops.contains("public Result restore"));
        assertTrue(ops.contains("setRecipeEnabled"));
        assertTrue(factory.contains("public static boolean open"));
        assertTrue(factory.contains("ShanhaiRecipeEditorWorkspace"));
    }
}
