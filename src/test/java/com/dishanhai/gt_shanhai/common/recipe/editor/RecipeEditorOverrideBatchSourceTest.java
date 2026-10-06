package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorOverrideBatchSourceTest {

    @Test
    void rebuildAndEditorQueriesLoadOverridesOncePerRecipeType() throws Exception {
        String rebuild = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java"));
        String query = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java"));
        String vanilla = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeRebuild.java"));
        String base = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeBase.java"));

        assertTrue(rebuild.contains("buildCanonicalList(String recipeTypeId"));
        assertTrue(query.contains("RecipeRebuildService.buildCanonicalList(typeId,"));
        assertTrue(vanilla.contains("RecipeRebuildService.buildCanonicalList(typeId,"));
        assertTrue(base.contains("legacySnapshotFingerprint"));
        assertTrue(base.contains("inheritLegacyDetails"));
    }
}
