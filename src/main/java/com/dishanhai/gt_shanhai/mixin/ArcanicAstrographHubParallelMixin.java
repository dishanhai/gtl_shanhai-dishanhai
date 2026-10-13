package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph;
import com.mojang.datafixers.util.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 奥术星图的宇宙模拟把并行写死在 {@code getParallelAmount()}，不读并行仓。
 * 枢纽并行更高时改用枢纽值；缩放失败则退回机器原并行，避免巨大产出把配方打成 0 并行。
 */
@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph$Companion$ArcanicAstrographRecipeLogic", remap = false)
public class ArcanicAstrographHubParallelMixin {

    @Redirect(
            method = "findCosmosSimulationRecipe",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/gregtechceu/gtceu/common/data/GTRecipeModifiers;accurateParallel(Lcom/gregtechceu/gtceu/api/machine/MetaMachine;Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;IZ)Lcom/mojang/datafixers/util/Pair;"
            ),
            remap = false
    )
    private Pair<GTRecipe, Integer> gtShanhai$applyHubParallel(MetaMachine machine, GTRecipe recipe,
                                                                int maxParallel, boolean modifyDuration) {
        int limit = maxParallel;
        if (machine instanceof ArcanicAstrograph astrograph) {
            int hatchParallel = gtShanhai$hubParallel(astrograph);
            if (hatchParallel > limit) {
                limit = hatchParallel;
            }
        }
        Pair<GTRecipe, Integer> result = GTRecipeModifiers.accurateParallel(machine, recipe, limit, modifyDuration);
        int applied = result == null || result.getSecond() == null ? 0 : result.getSecond();
        if (limit > maxParallel && applied < maxParallel) {
            return ParallelLogic.applyParallel(machine, recipe, maxParallel, modifyDuration);
        }
        return result;
    }

    private static int gtShanhai$hubParallel(ArcanicAstrograph machine) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get() || machine == null || !machine.isFormed()) {
            return 0;
        }
        int best = 0;
        for (IMultiPart part : machine.getParts()) {
            if (part instanceof DShanhaiMaintenanceHatchMachine hatch) {
                int current = hatch.getCurrentParallel();
                if (current > best) {
                    best = current;
                }
            }
        }
        return best;
    }
}
