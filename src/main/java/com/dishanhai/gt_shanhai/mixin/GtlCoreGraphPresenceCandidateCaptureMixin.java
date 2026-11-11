package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.common.item.GraphPresenceDispatchBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog$CandidateCapture",
        priority = 1500, remap = false)
public abstract class GtlCoreGraphPresenceCandidateCaptureMixin {

    @Shadow @Final IPatternDetails.IInput[] inputs;
    @Shadow int inputSlot;

    @WrapMethod(method = "step", remap = false)
    private boolean gtShanhai$capturePresenceInput(Operation<Boolean> original) {
        IPatternDetails.IInput input = this.inputSlot >= 0 && this.inputSlot < this.inputs.length
                ? this.inputs[this.inputSlot] : null;
        GraphPresenceDispatchBridge.enterInput(input);
        try {
            return original.call();
        } finally {
            GraphPresenceDispatchBridge.clear();
        }
    }
}
