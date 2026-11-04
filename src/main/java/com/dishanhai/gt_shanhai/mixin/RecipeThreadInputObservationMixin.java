package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipeRunnerHelper.class, remap = false)
public abstract class RecipeThreadInputObservationMixin {
    @Inject(method = "handleRecipeInput", at = @At("HEAD"), cancellable = true)
    private static void shanhai$guardAdmission(IRecipeLogicMachine machine, GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        Boolean decision = NativeRecipeThreadRuntime.inputDecision(machine, recipe);
        if (decision != null) cir.setReturnValue(decision);
    }
    @Inject(method = "handleRecipeInput", at = @At("RETURN"))
    private static void shanhai$recordPaid(IRecipeLogicMachine machine, GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) NativeRecipeThreadRuntime.recordInput(machine, recipe);
    }
}
