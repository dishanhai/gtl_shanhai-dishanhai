package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import com.mojang.datafixers.util.Pair;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 核弹级并行覆写：拦截 RecipeLogic.setupRecipe()。
 * 从枢纽读取原始 long 并行/线程值，支持 Long.MAX_VALUE 级并行。
 */
@Mixin(value = RecipeLogic.class, remap = false)
public class NuclearParallelMixin {


    @ModifyVariable(
            method = "setupRecipe",
            at = @At("HEAD"),
            ordinal = 0,
            remap = false
    )
    private GTRecipe gtShanhai$nuclearParallel(GTRecipe recipe) {
        try {
            if (!DShanhaiConfig.COMMON.parallelForceMode.get()) return recipe;
            if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return recipe;
            if (!DShanhaiConfig.COMMON.hubOutputMultiplier.get()) return recipe;

            // 已有多配方并行的跳过
            if (recipe instanceof IGTRecipe igt && igt.getRealParallels() > 1) return recipe;

            MetaMachine machine = ((RecipeLogic) (Object) this).getMachine();
            if (!(machine instanceof IMultiController controller)) return recipe;
            if (!controller.isFormed()) return recipe;
            if (gtShanhai$isGTLAddExternalMachine(controller)) return recipe;

            // 从枢纽读取原始 long 并行 + 线程值
            long rawParallel = 1;
            long rawThread = 1;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine dh) {
                    long p = dh.getRawParallel();
                    if (p > rawParallel) rawParallel = p;
                    long t = dh.getRawThreadCount();
                    if (t > rawThread) rawThread = t;
                } else if (part instanceof IParallelHatch h) {
                    int p = h.getCurrentParallel();
                    if (p > rawParallel) rawParallel = p;
                }
            }

            // 最终并行 = 并行 × 线程（支持 Long.MAX_VALUE）
            long totalParallel;
            try {
                totalParallel = Math.multiplyExact(rawParallel, rawThread);
            } catch (ArithmeticException e) {
                totalParallel = Long.MAX_VALUE;
            }
            if (totalParallel <= 1) return recipe;

            // Long.MAX_VALUE 路径：手动 ContentModifier
            if (totalParallel > Integer.MAX_VALUE) {
                ContentModifier modifier = ContentModifier.multiplier((double) totalParallel);
                GTRecipe modified = recipe.copy(modifier, false);
                if (modified instanceof IGTRecipe igt) {
                    igt.setRealParallels(totalParallel);
                }
                modified.duration = Math.max(1, (int) (recipe.duration / (double) totalParallel));
                return modified;
            }

            // 普通 int 路径：ParallelLogic.applyParallel
            int intParallel = (int) totalParallel;
            Pair<GTRecipe, Integer> result = ParallelLogic.applyParallel(machine, recipe, intParallel, false);
            if (result != null) {
                GTRecipe modified = result.getFirst();
                if (modified instanceof IGTRecipe igt) {
                    igt.setRealParallels(intParallel);
                }
                return modified;
            }
        } catch (Exception e) {

        }
        return recipe;
    }

    private static boolean gtShanhai$isGTLAddExternalMachine(IMultiController controller) {
        String name = controller.getClass().getName();
        return name.startsWith("com.gtladd.gtladditions.common.machine.multiblock.controller.")
                || name.startsWith("com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.");
    }
}
