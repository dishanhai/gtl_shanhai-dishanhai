package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.pattern.AEProcessingPattern;

import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPatternInputExtractor;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AEUtils.class, remap = false)
public class AeUtilsVirtualPatternInputsMixin {

    @Inject(
            method = "extractForProcessingPattern(Lappeng/crafting/pattern/AEProcessingPattern;"
                    + "Lappeng/crafting/inv/ICraftingInventory;Lappeng/api/stacks/KeyCounter;J)"
                    + "[Lappeng/api/stacks/KeyCounter;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void gtShanhai$extractReusablePresenceInputs(AEProcessingPattern details,
            ICraftingInventory sourceInv, KeyCounter expectedOutputs, long multiplier,
            CallbackInfoReturnable<KeyCounter[]> cir) {
        if (!VirtualPatternEncodingHelper.containsVirtualProviderPattern(details)) return;
        cir.setReturnValue(VirtualCraftingPatternInputExtractor.extractBulk(
                details, sourceInv, expectedOutputs, multiplier));
    }
}
