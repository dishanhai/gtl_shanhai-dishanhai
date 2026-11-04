package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.thread.NativeRecipeThreadRuntime;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ConversationMachine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Limits simulation and delayed settlement; leaves the machine's raw long capacity unchanged. */
@Mixin(value = ConversationMachine.class, remap = false)
public abstract class RecipeThreadConversationMachineMixin {
    @ModifyExpressionValue(method = {"beforeWork", "afterWorking"}, at = @At(value = "FIELD",
            target = "Lcom/gtladd/gtladditions/common/machine/multiblock/controller/ConversationMachine;parallel:J"))
    private long shanhai$limitTransaction(long nativeParallel) {
        return NativeRecipeThreadRuntime.transactionParallel(this, nativeParallel);
    }
}
