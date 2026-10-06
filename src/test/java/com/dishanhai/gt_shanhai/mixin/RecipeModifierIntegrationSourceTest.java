package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeModifierIntegrationSourceTest {

    @Test
    void apiExposesGlobalAndTypeRecipeRevisions() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java"));

        assertTrue(api.contains("public static long getRecipeRevision()"));
        assertTrue(api.contains("public static long getRecipeTypeRevision(String recipeTypeId)"));
        assertTrue(api.contains("public static void invalidateRecipeCaches(String reason, Set<String> typeIds)"));
    }

    @Test
    void lookupMixinUsesCanonicalBoundaryAndRevisionAwareCache() throws Exception {
        String mixin = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/mixin/RecipeModifierAPIMixin.java"));
        String cache = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRuntimeRecipeCache.java"));

        assertTrue(mixin.contains("prepareLookupRecipe"));
        assertTrue(mixin.contains("DShanhaiRuntimeRecipeCache.key"));
        assertTrue(cache.contains("getRecipeTypeRevision"));
    }
}
