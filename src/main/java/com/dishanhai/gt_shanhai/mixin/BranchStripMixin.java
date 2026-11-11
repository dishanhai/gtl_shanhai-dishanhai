package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeLookupOverflow;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import java.util.List;
import java.util.stream.Stream;

/**
 * 注入 Branch.getRecipes(boolean) 的 RETURN，对返回的 Stream 中每个配方应用剥离规则。
 * JEI 通过此方法获取配方列表，此 Mixin 确保 JEI 显示的配方也被剥离。
 */
@Mixin(value = Branch.class, remap = false)
public class BranchStripMixin {

    @ModifyReturnValue(method = "getRecipes", at = @At("RETURN"))
    private Stream<GTRecipe> gtShanhai$stripBranchRecipes(Stream<GTRecipe> stream) {
        if (stream == null) return null;
        List<GTRecipe> overflow = RecipeLookupOverflow.recipes((Branch) (Object) this);
        if (!overflow.isEmpty()) stream = Stream.concat(stream, overflow.stream());
        if (DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.get()) return stream;

        return stream.map(recipe -> {
            if (recipe == null) return null;
            if (DShanhaiRecipeModifierAPI.isCanonicalLookupRecipe(recipe)) return recipe;
            if (recipe.recipeType == null || recipe.recipeType.registryName == null) return recipe;
            if (!DShanhaiRecipeModifierAPI.hasRuntimeStripOrReplaceRules(recipe.recipeType.registryName.toString())) return recipe;
            GTRecipe copy = recipe.copy();
            DShanhaiRecipeModifierAPI.applyStripByType(copy);
            DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
            return copy;
        });
    }
}
