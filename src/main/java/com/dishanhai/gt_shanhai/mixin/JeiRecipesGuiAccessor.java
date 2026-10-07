package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RecipesGui.class, remap = false)
public interface JeiRecipesGuiAccessor {

    @Accessor(value = "logic", remap = false)
    IRecipeGuiLogic gtShanhai$getRecipeGuiLogic();
}
