package com.dishanhai.gt_shanhai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 批量覆写 gtladditions 中独立并行计算的机器为无限并行。
 * 原初三兄弟（BiologicalSimulationLaboratory/DimensionFocusInfinityCraftingArray/TaixuTurbidArray）特殊处理，不参与覆写。
 */
@Mixin(targets = {
    "com.gtladd.gtladditions.common.machine.multiblock.controller.SkeletonShiftRiftEngine",
    "com.gtladd.gtladditions.common.machine.multiblock.controller.AdvancedSpaceElevatorModuleMachine",
    "com.gtladd.gtladditions.common.machine.multiblock.controller.HeartOfTheUniverse"
}, remap = false)
public class GTLAddParallelOverrideMixin {

    @Inject(method = "getMaxParallel", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$infiniteParallel(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Integer.MAX_VALUE);
    }
}
