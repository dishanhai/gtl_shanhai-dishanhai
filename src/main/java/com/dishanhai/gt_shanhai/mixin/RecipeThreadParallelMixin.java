package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ParallelLogic.class, remap = false)
public abstract class RecipeThreadParallelMixin {
    @ModifyVariable(method = "doParallelRecipes", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int shanhai$partitionParallel(int nativeParallel) {
        return (int) Math.min(Integer.MAX_VALUE, NativeRecipeThreadRuntime.limitParallel(nativeParallel));
    }
}
