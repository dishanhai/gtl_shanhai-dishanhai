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
        String rebuild = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java"));
        String commit = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorCommitPacket.java"));
        String widget = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorWidget.java"));

        assertTrue(query.contains("query(String typeFilter, String text, int page, int pageSize)"));
        assertTrue(query.contains("currentRevision()"));
        assertTrue(ops.contains("preview(Edit edit)"));
        assertTrue(ops.contains("commit(Edit edit)"));
        assertTrue(ops.contains("rollback(String recipeId)"));
        assertTrue(sync.contains("revision"));
        assertTrue(rebuild.contains("entriesForType(recipeTypeId)"));
        assertTrue(rebuild.contains("override.toGtRecipe(copy)"));
        assertTrue(commit.contains("sender.hasPermissions(2)"));
        assertTrue(commit.contains("RecipeOriginalSnapshotStore.copyOf"));
        assertTrue(widget.contains("selectedBase.tickOutputs()"));
        assertTrue(widget.contains("json.has(\"tickOutputs\")"));
    }

    @Test
    void selectedMachineTypeQueriesLiveLookupWhenNoSnapshotWasCapturedYet() throws Exception {
        String query = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java"));
        String rebuild = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java"));

        assertTrue(query.contains("typeIds.add(type)"));
        assertTrue(query.contains("RecipeRebuildService.originalsOfType(typeId)"));
        assertTrue(rebuild.contains("public static List<GTRecipe> originalsOfType(String recipeTypeId)"));
    }
}
