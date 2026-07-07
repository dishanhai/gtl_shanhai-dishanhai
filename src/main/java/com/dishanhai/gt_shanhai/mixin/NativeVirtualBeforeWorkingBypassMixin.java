package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.NativeVirtualSetupState;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 虚拟跨类型配方执行时的 beforeWorking 绕过。
 *
 * <p>StorageMachine（WorkableMultiblockMachine 子类）的 beforeWorking 会遍历
 * 所有 parts 调 part.beforeWorking(machine)。当宿主当前模式与虚拟配方类型不一致时，
 * 部分 part 会拒绝（返回 false），导致 setupRecipe 回滚到 IDLE。
 *
 * <p>当 NativeVirtualSetupState.isVirtualExecution() 为 true 时（由
 * NativeVirtualFindHandleRecipeMixin 在调用 setupRecipe 前设置），跳过 parts
 * 检查直接返回 true，允许跨类型配方进入工作状态。
 */
@Mixin(value = WorkableMultiblockMachine.class, remap = false)
public class NativeVirtualBeforeWorkingBypassMixin {

    @Inject(method = "beforeWorking", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$bypassBeforeWorkingForVirtual(
            @Nullable GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (!NativeVirtualSetupState.isVirtualExecution()) return;
        // 虚拟跨类型配方：跳过宿主模式校验，直接放行
        cir.setReturnValue(true);
    }
}
