package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 维护仓条件绕过：注入 GTRecipe.checkConditions。
 * 检测多方块主机是否有维护仓，有则绕过清洁室/维度/研究等条件。
 * CWU 绕过由 InfiniteCWUContainer trait 处理（维护仓自带无限算力）。
 */
@Mixin(value = GTRecipe.class, remap = false)
public class MaintenanceBypassMixin {

    @Inject(method = "checkConditions", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$bypassCondition(RecipeLogic recipeLogic, CallbackInfoReturnable<GTRecipe.ActionResult> cir) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;

        try {
            if (recipeLogic == null || recipeLogic.machine == null) return;
            var metaMachine = (MetaMachine) ((IMachineFeature) recipeLogic.machine).self();
            if (!(metaMachine instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;

            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IMaintenanceBypassPart bp && bp.isConditionBypassEnabled()) {
                    cir.setReturnValue(GTRecipe.ActionResult.SUCCESS);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }
}
