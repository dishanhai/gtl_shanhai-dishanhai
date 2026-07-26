package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.util.HubMachineHelper;
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
        if (!HubMachineHelper.hasHub(machine)) {
            trait.resetCompression();
        }
    }
}
