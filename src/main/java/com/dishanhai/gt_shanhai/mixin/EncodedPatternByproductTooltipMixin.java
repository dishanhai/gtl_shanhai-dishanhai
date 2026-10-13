package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "appeng.crafting.pattern.EncodedPatternItem")
public class EncodedPatternByproductTooltipMixin {

    @Inject(method = "m_7373_", at = @At("RETURN"), remap = false)
    private void gtShanhai$byproductOrderTooltip(ItemStack stack, Level level, List<Component> lines,
            TooltipFlag flag, CallbackInfo ci) {
        PatternByproductOrder.appendTooltip(stack, lines);
    }
}
