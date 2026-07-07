package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph;
import com.gtladd.gtladditions.common.machine.trait.AstralArrayCompressionTrait;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AstralArrayCompressionTrait.class, remap = false)
public class AstralArrayCompressionTraitMixin {

    @Inject(method = "getCompressedAstralArrayOutputChance", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$forceFullOutputChance(CallbackInfoReturnable<Double> cir) {
        ArcanicAstrograph machine = ((AstralArrayCompressionTrait) (Object) this).getMachine();
        if (gtShanhai$hasHub(machine)) {
            cir.setReturnValue(1.0D);
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
