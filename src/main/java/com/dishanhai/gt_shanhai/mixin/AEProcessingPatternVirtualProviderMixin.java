package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AEProcessingPattern.class, remap = false)
public class AEProcessingPatternVirtualProviderMixin {

    @Shadow @Final private GenericStack[] sparseInputs;
    @Shadow @Final private GenericStack[] sparseOutputs;

    @Inject(method = "getInputs", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$getPlanningInputsWithoutVirtualProviders(CallbackInfoReturnable<IPatternDetails.IInput[]> cir) {
        if (VirtualPatternEncodingHelper.containsVirtualProviderInput(sparseInputs, sparseOutputs)) {
            cir.setReturnValue(VirtualPatternEncodingHelper.createPlanningInputs(sparseInputs, sparseOutputs));
        }
    }

    @Inject(method = "pushInputsToExternalInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$pushVirtualProvidersToMachine(KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink, CallbackInfo ci) {
        if (!VirtualPatternEncodingHelper.containsVirtualProviderInput(sparseInputs, sparseOutputs)) {
            return;
        }
        VirtualPatternEncodingHelper.pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink);
        ci.cancel();
    }
}
