package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternParallelState;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import com.gtladd.gtladditions.common.data.ParallelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：觀測 GTLAdd 本輪分配提案；是否可延後仍由主機實際進度決定。 */
@Mixin(value = GTLAddMultipleRecipesLogic.class, remap = false)
public abstract class StellarPatternAddParallelObservationMixin {
    @Inject(method = "calculateParallels", at = @At("RETURN"), remap = false)
    private void gtShanhai$observeParallelAllocation(CallbackInfoReturnable<ParallelData> cir) {
        long total = 0L;
        ParallelData allocation = cir.getReturnValue();
        if (allocation != null) {
            for (long amount : allocation.getParallels()) {
                long positive = Math.max(0L, amount);
                total = positive > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + positive;
            }
        }
        ((StellarPatternParallelState) this).gtShanhai$recordAllocatedParallel(total);
    }
}
