package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiFluidTooltipAPI;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 JEI 配方页流体槽位 tooltip 中注入山海流体的描述行。
 * 目标: mezz.jei.library.gui.ingredients.RecipeSlot#getTooltip(ITooltipBuilder)
 */
@Mixin(targets = "mezz.jei.library.gui.ingredients.RecipeSlot", remap = false)
public class RecipeSlotTooltipMixin {

    @Shadow(remap = false)
    private java.util.Optional<mezz.jei.api.ingredients.ITypedIngredient<?>> getDisplayedIngredient() { return null; }

    @Inject(method = "getTooltip(Lmezz/jei/api/gui/builder/ITooltipBuilder;)V", at = @At("TAIL"), remap = false)
    private void addShanhaiFluidTooltip(ITooltipBuilder tooltipBuilder, CallbackInfo ci) {
        try {
            var opt = getDisplayedIngredient();
            if (opt.isEmpty()) return;

            var ingredient = opt.get().getIngredient();
            if (!(ingredient instanceof FluidStack fluidStack)) return;

            String fluidId = fluidStack.getFluid().builtInRegistryHolder().key().location().toString();
            String[] lines = DShanhaiFluidTooltipAPI.getEntryLines(fluidId);
            if (lines == null || lines.length == 0) return;

            for (String line : lines) {
                try {
                    tooltipBuilder.add(ShanhaiTextAPI.inline(line));
                } catch (Exception e) {
                    tooltipBuilder.add(Component.literal("§7" + line));
                }
            }
        } catch (Exception e) {
            // 静默失败，不影响 tooltip
        }
    }
}
