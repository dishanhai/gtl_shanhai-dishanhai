package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorBackendContractSourceTest {

    @Test
    void backendContractExposesQueryPreviewCommitRollbackAndRevision() throws Exception {
        String query = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java"));
        String ops = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOps.java"));
        String sync = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeSyncPacket.java"));

        assertTrue(query.contains("query(String typeFilter, String text, int page, int pageSize)"));
        assertTrue(query.contains("currentRevision()"));
        assertTrue(ops.contains("preview(Edit edit)"));
        assertTrue(ops.contains("commit(Edit edit)"));
        assertTrue(ops.contains("rollback(String recipeId)"));
        assertTrue(sync.contains("revision"));
    }
}
