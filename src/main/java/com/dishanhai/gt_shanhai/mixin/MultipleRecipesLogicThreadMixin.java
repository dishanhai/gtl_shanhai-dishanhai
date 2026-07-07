package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强行覆写 MultipleRecipesLogic（gtlcore）的 getMaxParallel 返回值，
 * 将天球分歧引擎的线程数附加到并行预算中。
 * 解决纳米锻炉等 gtlcore 多配方机器没有线程覆写的问题。
 */
@Mixin(value = org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic.class, remap = false)
public abstract class MultipleRecipesLogicThreadMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:thread");

    @Shadow(remap = false)
    public abstract org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine getParallel();

    @Inject(method = "getEuMultiplier", at = @At("RETURN"), cancellable = true, require = 0)
    private void gtShanhai$threadEuMultiplier(CallbackInfoReturnable<Double> cir) {
        try {
            var pm = getParallel();
            double multiplier = cir.getReturnValue();
            boolean changed = false;

            if (pm instanceof IMultiController controller && controller.isFormed()) {
                int totalThreads = 0;
                for (IMultiPart part : controller.getParts()) {
                    if (part instanceof IThreadModifierPart tp) {
                        int t = tp.getThreadCount();
                        if (t > totalThreads) totalThreads = t;
                    }
                }
                if (totalThreads > 0) {
                    multiplier /= Math.max(1, totalThreads);
                    changed = true;
                }
            }

            if (pm instanceof PrimordialOmegaEngineModuleBase module) {
                double mountMultiplier = module.getExtraMountEuMultiplier();
                if (mountMultiplier != 1.0D) {
                    multiplier *= mountMultiplier;
                    changed = true;
                }
            }

            if (changed) {
                cir.setReturnValue(multiplier);
            }
        } catch (Exception e) {
            LOG.error("[threadEuMultiplier] 异常", e);
        }
    }
}
