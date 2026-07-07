package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;

@Mixin(value = RecipeLogic.class, remap = false)
public class ProgrammableHatchRecipeTypeSearchMixin {

    @Inject(method = "searchRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$searchRecipeWithPatternRecipeTypes(CallbackInfoReturnable<Iterator<GTRecipe>> cir) {
        RecipeLogic self = (RecipeLogic) (Object) this;
        cir.setReturnValue(RecipeTypePatternSearchHelper.searchRecipe(self.machine));
    }
}
