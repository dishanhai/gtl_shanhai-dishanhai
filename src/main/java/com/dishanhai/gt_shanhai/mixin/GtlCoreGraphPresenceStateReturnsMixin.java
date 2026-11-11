package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlStateReturns", remap = false)
public abstract class GtlCoreGraphPresenceStateReturnsMixin {

    // The native processing input has an exact, empty physical remainder even for tools.
    @ModifyVariable(method = "capture", at = @At("HEAD"), argsOnly = true, remap = false)
    private static IPatternDetails.IInput gtShanhai$capturePhysicalRemainder(IPatternDetails.IInput input) {
        if (!VirtualPatternEncodingHelper.isPresenceInput(input)) return input;
        return VirtualPatternEncodingHelper.nativeProcessingInput(
                input.getPossibleInputs()[0].what(), input.getMultiplier());
    }
}
