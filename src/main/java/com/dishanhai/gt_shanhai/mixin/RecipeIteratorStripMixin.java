package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.RecipeIterator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 RecipeIterator.next() RETURN。
 * 所有通过迭代器获取配方的路径（多配方机的 lookupRecipeIterator 等）都会经过这里。
 * 在配方返回给调用者之前，就地剥离已注册的规则。
 */
@Mixin(value = RecipeIterator.class, remap = false)
public class RecipeIteratorStripMixin {

    @Inject(method = "next", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$stripOnNext(CallbackInfoReturnable<GTRecipe> cir) {
        GTRecipe recipe = cir.getReturnValue();
        if (recipe == null) return;
        if (recipe.recipeType == null || recipe.recipeType.registryName == null) return;
        if (!DShanhaiRecipeModifierAPI.hasRuntimeStripRules(recipe.recipeType.registryName.toString())) return;
        GTRecipe copy = recipe.copy();
        DShanhaiRecipeModifierAPI.applyStripByType(copy);
        cir.setReturnValue(copy);
    }
}
