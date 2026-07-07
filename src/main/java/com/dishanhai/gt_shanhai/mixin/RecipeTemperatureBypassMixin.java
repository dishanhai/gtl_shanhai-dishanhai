package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTempBypass;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RecipeLogic + MultipleRecipesLogic: findAndHandleRecipe 前设 bypass flag
 * searchRecipe 拦截在 SearchRecipeTempMixin 单独处理（因 MultipleRecipesLogic 无此方法）
 */
@Mixin(value = {RecipeLogic.class, MultipleRecipesLogic.class})
public class RecipeTemperatureBypassMixin {

    @Inject(method = "findAndHandleRecipe", at = @At("HEAD"), remap = false)
    private void gtShanhai$markBypass(CallbackInfo ci) {
        try {
            RecipeLogic self = (RecipeLogic) (Object) this;
            var machine = self.getMachine();
            if (!(machine instanceof IMultiController controller)) { return; }
            if (!controller.isFormed()) { return; }
            boolean found = false;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine hatch && hatch.isTemperatureBypassEnabled()) {
                    found = true; break;
                }
            }
            DShanhaiTempBypass.set(found);
        } catch (Exception e) { DShanhaiTempBypass.set(false); }
    }

    @Inject(method = "findAndHandleRecipe", at = @At("RETURN"), remap = false)
    private void gtShanhai$clearBypass(CallbackInfo ci) {
        DShanhaiTempBypass.set(false);
    }
}
