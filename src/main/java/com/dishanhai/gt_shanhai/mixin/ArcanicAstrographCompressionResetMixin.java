package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph;
import com.gtladd.gtladditions.common.machine.trait.AstralArrayCompressionTrait;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph$Companion$ArcanicAstrographRecipeLogic", remap = false)
public class ArcanicAstrographCompressionResetMixin {

    @Redirect(
            method = {"findAndHandleRecipe", "onRecipeFinish"},
            at = @At(value = "INVOKE", target = "Lcom/gtladd/gtladditions/common/machine/trait/AstralArrayCompressionTrait;resetCompression()V")
    )
    private void gtShanhai$keepHubCompressionProgress(AstralArrayCompressionTrait trait) {
        ArcanicAstrograph machine = trait.getMachine();
        if (!gtShanhai$hasHub(machine)) {
            trait.resetCompression();
        }
    }

    private static boolean gtShanhai$hasHub(IMultiController controller) {
        try {
            if (controller == null || !controller.isFormed()) {
                return false;
            }
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
