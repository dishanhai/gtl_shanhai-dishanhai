package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MetaMachine.class, remap = false)
public class ArcanicAstrographHubCompressionMixin {

    private static final long GT_SHANHAI_COMPRESSION_COLLECT_INTERVAL = 10L;
    private static final long GT_SHANHAI_COMPRESSION_FINISH_INTERVAL = 600L;

    @Inject(method = "serverTick", at = @At("TAIL"))
    private void gtShanhai$driveHubCompression(CallbackInfo ci) {
        MetaMachine meta = (MetaMachine) (Object) this;
        if (!(meta instanceof ArcanicAstrograph machine)) {
            return;
        }
        if (!gtShanhai$hasHub(machine)) {
            return;
        }
        RecipeLogic logic = machine.getRecipeLogic();
        if (!logic.isWorkingEnabled()) {
            machine.getAstralArrayCompression().resetCompression();
            return;
        }

        long timer = machine.getOffsetTimer();
        if (timer % GT_SHANHAI_COMPRESSION_COLLECT_INTERVAL == 0L) {
            machine.getAstralArrayCompression().handleCompressionWorking();
        }
        if (timer % GT_SHANHAI_COMPRESSION_FINISH_INTERVAL == 0L) {
            machine.getAstralArrayCompression().finishCompression();
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
