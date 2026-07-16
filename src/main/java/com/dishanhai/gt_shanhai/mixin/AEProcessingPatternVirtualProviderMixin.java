package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AEProcessingPattern.class, remap = false)
public class AEProcessingPatternVirtualProviderMixin {

    @Shadow @Final private GenericStack[] sparseInputs;
    @Shadow @Final private GenericStack[] sparseOutputs;

    @Unique
    private volatile IPatternDetails.IInput[] gtShanhai$cachedPlanningInputs;

    @Unique
    private volatile long gtShanhai$cachedPlanningRevision = Long.MIN_VALUE;

    @Inject(method = "getInputs", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$getPlanningInputsWithoutVirtualProviders(CallbackInfoReturnable<IPatternDetails.IInput[]> cir) {
        IPatternDetails.IInput[] planningInputs = gtShanhai$getPlanningInputs();
        if (planningInputs != null) cir.setReturnValue(planningInputs);
    }

    @Inject(method = "pushInputsToExternalInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$pushVirtualProvidersToMachine(KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink, CallbackInfo ci) {
        if (gtShanhai$getPlanningInputs() == null) return;
        VirtualPatternEncodingHelper.pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink);
        ci.cancel();
    }

    @Unique
    private IPatternDetails.IInput[] gtShanhai$getPlanningInputs() {
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        if (this.gtShanhai$cachedPlanningRevision != revision) {
            IPatternDetails.IInput[] planningInputs = null;
            if (VirtualPatternEncodingHelper.containsVirtualProviderInput(this.sparseInputs, this.sparseOutputs)) {
                planningInputs = VirtualPatternEncodingHelper.createPlanningInputs(this.sparseInputs, this.sparseOutputs);
            }
            this.gtShanhai$cachedPlanningInputs = planningInputs;
            this.gtShanhai$cachedPlanningRevision = revision;
        }
        return this.gtShanhai$cachedPlanningInputs;
    }
}
