package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Set;

public final class ShanhaiVanillaRecipeRebuild {

    private ShanhaiVanillaRecipeRebuild() {}

    public static void rebuild(MinecraftServer server, Set<String> typeIds) {
        if (server == null || typeIds == null) return;
        for (String typeId : typeIds) {
            List<GTRecipe> originals = RecipeOriginalSnapshotStore.copiesOf(typeId);
            List<GTRecipe> canonical = RecipeRebuildService.buildCanonicalList(typeId, originals);
            ShanhaiVanillaRecipeTable.replaceType(server, typeId, canonical);
        }
        DShanhaiRecipeModifierAPI.invalidateRecipeCaches("vanilla-table-rebuild", typeIds);
    }
}
