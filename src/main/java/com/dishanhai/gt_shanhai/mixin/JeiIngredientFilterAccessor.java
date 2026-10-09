package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.gui.ingredients.IngredientFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = IngredientFilter.class, remap = false)
public interface JeiIngredientFilterAccessor {

    @Invoker("notifyListenersOfChange")
    void gtShanhai$notifyListenersOfChange();
}
