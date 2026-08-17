package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiFluidTooltipAPI;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * JEI 右侧流体面板 tooltip 注入。
 * 目标: mezz.jei.forge.platform.FluidHelper#getTooltip(List<Component>, FluidStack, TooltipFlag)
 */
@Mixin(targets = "mezz.jei.forge.platform.FluidHelper", remap = false)
public class FluidHelperTooltipMixin {

    @Inject(method = "getTooltip", at = @At("TAIL"), remap = false)
    private void addShanhaiFluidTooltip(List<Component> tooltip, FluidStack fluidStack, TooltipFlag tooltipFlag, CallbackInfo ci) {
        try {
            String fluidId = fluidStack.getFluid().builtInRegistryHolder().key().location().toString();
            String[] lines = DShanhaiFluidTooltipAPI.getEntryLines(fluidId);
            if (lines == null || lines.length == 0) return;

            for (String line : lines) {
                try {
                    tooltip.add(ShanhaiTextAPI.inline(line));
                } catch (Exception e) {
                    tooltip.add(Component.literal("§7" + line));
                }
            }
        } catch (Exception ignored) {}
    }
}
