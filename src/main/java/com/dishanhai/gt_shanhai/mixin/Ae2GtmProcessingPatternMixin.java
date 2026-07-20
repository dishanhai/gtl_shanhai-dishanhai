package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.ShanhaiPatternEncoder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.server.level.ServerPlayer;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Ae2GtmProcessingPattern.class, remap = false)
public class Ae2GtmProcessingPatternMixin {

    @Inject(method = "of", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$encodeNonConsumablesAsVirtualProviders(
            GTRecipe recipe, ServerPlayer player, CallbackInfoReturnable<Ae2GtmProcessingPattern> cir) {
        Ae2GtmProcessingPattern pattern = ShanhaiPatternEncoder.encode(recipe, player, true);
        if (pattern != null) {
            cir.setReturnValue(pattern);
        }
    }
}
