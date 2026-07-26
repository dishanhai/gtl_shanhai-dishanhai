package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IUniversalGravityMaintenancePart;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GravityCondition.class, remap = false)
public class GravityConditionMaintenanceMixin {

    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$provideBothGravityConditions(
            GTRecipe recipe, RecipeLogic recipeLogic, CallbackInfoReturnable<Boolean> cir) {
        // 宇宙洁净重力维护仓提供的同样是「绕过」能力，纳入 maintenance_hatch.enabled 总开关，
        // 与其余维护仓绕过 mixin 保持一致——此前这里漏读，管理员关掉开关后重力绕过仍然生效。
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;
        if (recipeLogic == null) return;
        if (!(recipeLogic.getMachine() instanceof MultiblockControllerMachine controller)
                || !controller.isFormed()) return;

        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IUniversalGravityMaintenancePart) {
                cir.setReturnValue(!((GravityCondition) (Object) this).isReverse());
                return;
            }
        }
    }
}
