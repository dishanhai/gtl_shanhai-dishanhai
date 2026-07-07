package com.dishanhai.gt_shanhai.mixin;

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
 * 强行覆写 GTLAddMultipleRecipesLogic.getMultipleThreads()，
 * 使山海机器的跨配方线程为无限（Integer.MAX_VALUE）。
 * 若分歧引擎安装到控制器上，分歧引擎的值优先于无限。
 */
@Mixin(value = com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic.class, remap = false)
public abstract class ThreadForceOverrideMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:thread");

    @Shadow(remap = false)
    protected abstract com.gtladd.gtladditions.api.machine.IGTLAddMultiRecipeMachine getLimited();

    @Inject(method = "getMultipleThreads", at = @At("RETURN"), cancellable = true, require = 0)
    private void gtShanhai$forceThreads(CallbackInfoReturnable<Integer> cir) {
        try {
            var limited = getLimited();
            if (!(limited instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;

            int maxThreads = 0;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IThreadModifierPart tp) {
                    int t = tp.getThreadCount();
                    if (t > maxThreads) maxThreads = t;
                }
            }

            if (maxThreads > 0 && maxThreads > cir.getReturnValue()) {
                cir.setReturnValue(maxThreads);
            }
            // else: 保持原值（枢纽线程 ≤ 原值时跳过，防覆写）
        } catch (Exception e) {
            LOG.error("[forceThreads] 异常", e);
        }
    }
}
