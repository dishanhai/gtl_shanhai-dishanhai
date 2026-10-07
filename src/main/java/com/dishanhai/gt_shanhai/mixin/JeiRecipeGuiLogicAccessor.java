package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.gui.recipes.RecipeGuiLogic;
import mezz.jei.gui.recipes.lookups.ILookupState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RecipeGuiLogic.class, remap = false)
public interface JeiRecipeGuiLogicAccessor {

    @Accessor(value = "state", remap = false)
    ILookupState gtShanhai$getLookupState();
}
