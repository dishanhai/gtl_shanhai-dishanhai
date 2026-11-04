package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import it.unimi.dsi.fastutil.longs.LongLongPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clamp candidate quantity before native construction; preserve the native allocation-cost component. */
@Mixin(targets = {
        "com.gtladd.gtladditions.common.modify.MutableMultiBlockModify$setOtherMutable$13$1$createRecipeLogic$1",
        "com.gtladd.gtladditions.common.modify.MutableMultiBlockModify$setOtherMutable$14$1$createRecipeLogic$1",
        "com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.MutablePCBFactoryMachine$createRecipeLogic$1"
}, remap = false)
public abstract class RecipeThreadSpecialMutableParallelMixin {
    @Inject(method = "calculateParallel", at = @At("RETURN"), cancellable = true)
    private void shanhai$limitCandidate(IRecipeLogicMachine machine, GTRecipe recipe, long nativeLimit,
                                       CallbackInfoReturnable<LongLongPair> cir) {
        LongLongPair candidate = cir.getReturnValue();
        if (candidate == null) return;
        long quantity = NativeRecipeThreadRuntime.limitParallel(candidate.firstLong());
        if (quantity != candidate.firstLong()) cir.setReturnValue(LongLongPair.of(quantity, candidate.secondLong()));
    }
}
