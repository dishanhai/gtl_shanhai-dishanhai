package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternParallelCapture;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 觀測共用輸入助手的結果，不改寫 getRecipe 或 GTLAdditions 的 recipe lookup。 */
@Mixin(value = RecipeRunnerHelper.class, remap = false)
public abstract class StellarPatternInputParallelObservationMixin {
    @Inject(method = "handleRecipeInput", at = @At("RETURN"), remap = false)
    private static void gtShanhai$observeConsumedParallel(IRecipeLogicMachine machine, GTRecipe recipe,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (machine == null || recipe == null || !Boolean.TRUE.equals(cir.getReturnValue())) return;
        var logic = machine.getRecipeLogic();
        if (!StellarPatternParallelCapture.isObserving(logic)) return;
        StellarPatternParallelCapture.record(logic, ((IGTRecipe) recipe).getRealParallels(), true);
    }
}
