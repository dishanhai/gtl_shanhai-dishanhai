package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.GraphPresenceDispatchBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.PatternFingerprint", remap = false)
public abstract class GtlCoreGraphPresenceFingerprintMixin {

    // Newer GTLCore goes through GtlStateReturns instead of calling the input directly.
    @WrapOperation(method = "capture", at = @At(value = "INVOKE",
            target = "Lappeng/api/crafting/IPatternDetails$IInput;getRemainingKey(Lappeng/api/stacks/AEKey;)Lappeng/api/stacks/AEKey;"),
            require = 0, remap = false)
    private static AEKey gtShanhai$capturePhysicalRemainder(IPatternDetails.IInput input, AEKey key,
            Operation<AEKey> original) {
        return GraphPresenceDispatchBridge.physicalRemainder(input, key, original::call);
    }

    @Pseudo
    @Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.PatternFingerprint$Context", remap = false)
    public static class Binding {

        @WrapOperation(method = "of(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/String;",
                at = @At(value = "INVOKE",
                        target = "Lappeng/api/crafting/IPatternDetails$IInput;getRemainingKey(Lappeng/api/stacks/AEKey;)Lappeng/api/stacks/AEKey;"),
                require = 0, remap = false)
        private AEKey gtShanhai$bindingPhysicalRemainder(IPatternDetails.IInput input, AEKey key,
                Operation<AEKey> original) {
            return GraphPresenceDispatchBridge.physicalRemainder(input, key, original::call);
        }
    }
}
