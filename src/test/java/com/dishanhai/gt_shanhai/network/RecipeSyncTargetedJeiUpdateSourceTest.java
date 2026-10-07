package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeSyncTargetedJeiUpdateSourceTest {

    @Test
    void editorDeltaOnlyReplacesTheAffectedJeiRecipeAndType() throws Exception {
        String sync = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeSyncPacket.java"));
        String cache = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/api/JEIRecipeCache.java"));
        String viewSync = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/recipe/ShanhaiJeiRecipeViewSync.java"));
        String mixins = Files.readString(Path.of("src/main/resources/gt_shanhai.mixin.json"));

        assertTrue(sync.contains("List.of(recipeTypeId)"),
                "single-recipe sync must not include every runtime-rule recipe type");
        assertTrue(sync.contains("targetedRecipeIds"),
                "client JEI replacement must be keyed by the changed recipe ID");
        assertTrue(sync.contains("JEIRecipeCache.removeRecipes(jeiType, targetedRecipeIds)"),
                "only the changed recipe must leave the deduplication cache");
        assertTrue(sync.contains("targetedRecipeIds.contains(wrapper.recipe.getId().toString())"),
                "only old wrappers for the changed recipe may be hidden");
        assertTrue(cache.contains("public static synchronized void removeRecipes"),
                "the JEI cache must support recipe-scoped invalidation");
        assertTrue(sync.contains("ShanhaiJeiRecipeViewSync.refreshCachedView"),
                "the current JEI view must be refreshed after the delta is installed");
        assertTrue(viewSync.contains("logic.showFocus(focuses)"),
                "a focused JEI lookup must be re-queried with its existing focus");
        assertTrue(viewSync.contains("new StaticFocusedRecipes"),
                "an open single-recipe view must replace only the affected recipe");
        assertTrue(mixins.contains("\"JeiRecipesGuiAccessor\""));
        assertTrue(mixins.contains("\"JeiRecipeGuiLogicAccessor\""));
    }
}
