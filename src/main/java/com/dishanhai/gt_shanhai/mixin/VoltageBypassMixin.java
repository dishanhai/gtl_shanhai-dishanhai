package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 WorkableMultiblockMachine.doModifyRecipe——所有主机配方处理必经之路。
 * 在此清除配方副本中的 EU/CWU，解除电压等级限制。
 */
@Mixin(value = com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine.class, remap = false)
public class VoltageBypassMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:voltage");

    @Inject(method = "doModifyRecipe", at = @At("HEAD"))
    private void gtShanhai$stripEUCWU(GTRecipe recipe, OCParams params, OCResult result,
                                       CallbackInfoReturnable<GTRecipe> cir) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;
        try {
            var meta = (MetaMachine) ((IMachineFeature) this).self();
            if (!(meta instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;

            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) {
                    recipe.inputs.remove(EURecipeCapability.CAP);
                    recipe.tickInputs.remove(EURecipeCapability.CAP);
                    recipe.tickInputs.remove(CWURecipeCapability.CAP);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }
}
