package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** This chance-aware override does not invoke its superclass candidate limiter. */
@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.ApocalypticTorsionQuantumMatrix$Companion$ApocalypticTorsionQuantumMatrixLogic", remap = false)
public abstract class RecipeThreadSpecialParallelMixin {
    @Inject(method = "getMaxParallel", at = @At("RETURN"), cancellable = true)
    private void shanhai$limitCandidate(GTRecipe recipe, long nativeLimit, CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(NativeRecipeThreadRuntime.limitParallel(cir.getReturnValue()));
    }
}
