package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 线圈机器 tooltip 温度行替换为彩虹「无限」。
 */
@Mixin(targets = "com.gregtechceu.gtceu.api.machine.multiblock.CoilWorkableElectricMultiblockMachine", remap = false)
public class EBFDisplayMixin {

    @Inject(method = "addDisplayText", at = @At("RETURN"), remap = false)
    private void gtShanhai$infiniteTempDisplay(List<Component> tooltips, CallbackInfo ci) {
        try {
            Object self = this;
            if (!(self instanceof IMultiController controller)) return;
            boolean hasBypass = false;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine hatch && hatch.isTemperatureBypassEnabled()) {
                    hasBypass = true;
                    break;
                }
            }
            if (!hasBypass) return;

            for (int i = 0; i < tooltips.size(); i++) {
                String text = tooltips.get(i).getString();
                if ((text.contains("K") || text.contains("k")) && (text.contains("热容") || text.contains("Temp"))) {
                    Component infinity = Component.literal("")
                            .append(Component.literal("§a热容：§b"))
                            .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                            .append(Component.literal("§aK"));
                    tooltips.set(i, infinity);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }
}
