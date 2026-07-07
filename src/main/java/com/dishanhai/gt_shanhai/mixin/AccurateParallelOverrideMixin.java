package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.mojang.datafixers.util.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 GTRecipeModifiers.accurateParallel()，当机器安装了维护仓且全面覆写开启时，
 * 用维护仓并行值替换原本的 8192 等硬限制。
 *
 * 纳米核心的配方修饰器调用 accurateParallel(recipe, 8192, false) 限制并行，
 * 此 mixin 在参数传入前截断，改为使用维护仓的并行值。
 */
@Mixin(value = GTRecipeModifiers.class, remap = false)
public class AccurateParallelOverrideMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:parallel");

    @Inject(method = "accurateParallel", at = @At("HEAD"), cancellable = true)
    private static void gtShanhai$overrideAccurateParallel(
            MetaMachine machine, GTRecipe recipe, int limit, boolean consume,
            CallbackInfoReturnable<Pair<GTRecipe, Integer>> cir) {
        try {
            if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;
            if (!DShanhaiConfig.COMMON.parallelForceMode.get()) return;
            if (!(machine instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;

            // 查找维护仓
            int hatchParallel = 0;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IMaintenanceBypassPart) {
                    // 取维护仓的并行值
                    if (part instanceof IParallelHatch hatch) {
                        int p = hatch.getCurrentParallel();
                        if (p > 0) {
                            hatchParallel = p;
                        }
                    }
                    break;
                }
            }
            if (hatchParallel <= 0 || hatchParallel == limit) return;

            // 用维护仓并行值替换原始限制
            var result = com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic.applyParallel(
                    machine, recipe, hatchParallel, consume);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[accurateParallel] {} limit={} → hatchParallel={}",
                        machine.getClass().getSimpleName(), limit, hatchParallel);
            }
            cir.setReturnValue(result);
        } catch (Exception e) {
            LOG.error("[accurateParallel] 异常", e);
        }
    }
}
