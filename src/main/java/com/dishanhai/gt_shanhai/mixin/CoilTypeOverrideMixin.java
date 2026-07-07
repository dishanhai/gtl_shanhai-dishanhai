package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTempBypass;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.block.ICoilType;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.CoilWorkableElectricMultiblockMachine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 源头替换线圈 + 虚拟线圈按配方要求返回温度。
 */
@Mixin(CoilWorkableElectricMultiblockMachine.class)
public class CoilTypeOverrideMixin {

    @Shadow(remap = false)
    private ICoilType coilType;

    private final ICoilType FAKE_COIL = new ICoilType() {
        @Override public String getName() { return "bypass"; }
        @Override public int getCoilTemperature() {
            int rt = DShanhaiTempBypass.getRecipeTemp();
            return rt > 0 ? rt + 1 : 96000;
        }
        @Override public int getLevel() { return Integer.MAX_VALUE; }
        @Override public int getEnergyDiscount() { return 1; }
        @Override public int getTier() { return 9; }
        @Override public Material getMaterial() { return null; }
        @Override public ResourceLocation getTexture() { return null; }
    };

    @Inject(method = "onStructureFormed", at = @At("RETURN"), remap = false)
    private void gtShanhai$overrideCoilType(CallbackInfo ci) {
        try {
            Object self = this;
            if (!(self instanceof IMultiController controller)) return;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine hatch && hatch.isTemperatureBypassEnabled()) {
                    this.coilType = FAKE_COIL;
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    @Inject(method = "getCoilTier", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$fixCoilTier(CallbackInfoReturnable<Integer> cir) {
        if (coilType == FAKE_COIL) cir.setReturnValue(9);
    }
}
