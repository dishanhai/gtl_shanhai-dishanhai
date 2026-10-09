package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.ingredients.IngredientFilterApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = IngredientFilterApi.class, remap = false)
public interface JeiIngredientFilterApiAccessor {

    @Accessor("ingredientFilter")
    IngredientFilter gtShanhai$getIngredientFilter();
}
