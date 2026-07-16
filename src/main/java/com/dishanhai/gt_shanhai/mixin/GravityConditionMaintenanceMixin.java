package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IUniversalGravityMaintenancePart;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GravityCondition.class, remap = false)
public class GravityConditionMaintenanceMixin {

    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$provideBothGravityConditions(
            GTRecipe recipe, RecipeLogic recipeLogic, CallbackInfoReturnable<Boolean> cir) {
        if (!(recipeLogic.getMachine() instanceof MultiblockControllerMachine controller)
                || !controller.isFormed()) return;

        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IUniversalGravityMaintenancePart) {
                cir.setReturnValue(!((GravityCondition) (Object) this).isReverse());
                return;
            }
        }
    }
}
