package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.misc.EnergyContainerList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在控制器 getEnergyContainer() 层面注入无限能源。
 * 替换 EnergyContainerList 为 InfinityEnergyContainerList，
 * 使 GTRecipeModifier.parallelizing 看到（电压×电流=∞），不会膨胀时长。
 */
@Mixin(value = com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine.class, remap = false)
public class InfinityEnergyMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:energy");

    @Inject(method = "getEnergyContainer", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$infinityEnergy(CallbackInfoReturnable<EnergyContainerList> cir) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;
        try {
            var meta = (MetaMachine) ((IMachineFeature) this).self();
            if (!(meta instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;

            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) {
                    // 动态读取电压，UI 改 tier 无需重新成型
                    cir.setReturnValue(bp.createInfinityEnergyContainer(
                            cir.getReturnValue(), bp::getBypassVoltage));
                    LOG.debug("[infinityEnergy] 注入无限能源 voltage={} machine={}",
                            bp.getBypassVoltage(), meta.getClass().getSimpleName());
                    return;
                }
            }
        } catch (Exception ignored) {}
    }
}
