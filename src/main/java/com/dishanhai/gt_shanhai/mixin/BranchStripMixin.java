package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * 注入 Branch.getRecipes(boolean) 的 RETURN，对返回的 Stream 中每个配方应用剥离规则。
 * JEI 通过此方法获取配方列表，此 Mixin 确保 JEI 显示的配方也被剥离。
 */
@Mixin(value = Branch.class, remap = false)
public class BranchStripMixin {

    @Inject(method = "getRecipes", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$stripBranchRecipes(boolean includeDisabled, CallbackInfoReturnable<Stream<GTRecipe>> cir) {
        if (DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.get()) return;

        Stream<GTRecipe> stream = cir.getReturnValue();
        if (stream == null) return;

        Stream<GTRecipe> stripped = stream.map(recipe -> {
            if (recipe == null) return null;
            if (recipe.recipeType == null || recipe.recipeType.registryName == null) return recipe;
            if (!DShanhaiRecipeModifierAPI.hasRuntimeStripOrReplaceRules(recipe.recipeType.registryName.toString())) return recipe;
            GTRecipe copy = recipe.copy();
            DShanhaiRecipeModifierAPI.applyStripByType(copy);
            DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
            return copy;
        });
        cir.setReturnValue(stripped);
    }
}
