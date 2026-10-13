package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.execution.CraftingCpuLogic;

import com.dishanhai.gt_shanhai.common.machine.part.SelfSynthesisCraftCancel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 物品已经发配进自合成仓后，取消这次合成任务。不回注样板产物。
 */
@Mixin(value = CraftingCpuLogic.class, priority = 1600, remap = false)
public class SelfSynthesisCraftCancelMixin {

    @Inject(method = "executeCrafting", at = @At("HEAD"), remap = false)
    private void gtShanhai$beginSelfSynthesisCancel(CallbackInfoReturnable<Integer> cir) {
        SelfSynthesisCraftCancel.begin();
    }

    @Inject(method = "executeCrafting", at = @At("RETURN"), remap = false)
    private void gtShanhai$cancelAfterSelfSynthesisDispatch(CallbackInfoReturnable<Integer> cir) {
        if (SelfSynthesisCraftCancel.finish()) {
            ((CraftingCpuLogic) (Object) this).cancel();
        }
    }
}
