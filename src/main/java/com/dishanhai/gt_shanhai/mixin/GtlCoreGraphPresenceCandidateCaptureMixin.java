package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.common.item.GraphPresenceDispatchBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog$CandidateCapture",
        priority = 1500, remap = false)
public abstract class GtlCoreGraphPresenceCandidateCaptureMixin {

    @Shadow @Final IPatternDetails.IInput[] inputs;
    @Shadow int inputSlot;

    @Inject(method = "step", at = @At("HEAD"), remap = false)
    private void gtShanhai$enterPresenceInput(CallbackInfoReturnable<Boolean> cir) {
        IPatternDetails.IInput input = this.inputSlot >= 0 && this.inputSlot < this.inputs.length
                ? this.inputs[this.inputSlot] : null;
        GraphPresenceDispatchBridge.enterInput(input);
    }

    @Inject(method = "step", at = @At("RETURN"), remap = false)
    private void gtShanhai$leavePresenceInput(CallbackInfoReturnable<Boolean> cir) {
        GraphPresenceDispatchBridge.clear();
    }
}
