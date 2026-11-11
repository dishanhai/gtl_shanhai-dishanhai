package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.GraphPresenceDispatchBridge;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlExecutionAdapter",
        priority = 1500, remap = false)
public abstract class GtlCoreGraphPresenceExecutionAdapterMixin {

    @ModifyExpressionValue(
            method = "matches",
            at = @At(value = "INVOKE",
                    target = "Lorg/gtlcore/gtlcore/integration/ae2/graph/GtlDispatchPolicy;configuration(Lappeng/api/stacks/AEKey;)Z"),
            remap = false)
    private boolean gtShanhai$presenceIsConfiguration(boolean original,
            @Local IPatternDetails.IInput input) {
        return original || VirtualPatternEncodingHelper.isPresenceInput(input);
    }

    @ModifyExpressionValue(
            method = "matches",
            at = @At(value = "INVOKE",
                    target = "Lorg/gtlcore/gtlcore/integration/ae2/graph/GtlDispatchPolicy;reusable(Lappeng/api/stacks/AEKey;)Z"),
            remap = false)
    private boolean gtShanhai$presenceIsReusable(boolean original,
            @Local IPatternDetails.IInput input) {
        return original || VirtualPatternEncodingHelper.isPresenceInput(input);
    }

    @WrapOperation(
            method = "matches",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/crafting/IPatternDetails$IInput;getRemainingKey(Lappeng/api/stacks/AEKey;)Lappeng/api/stacks/AEKey;"),
            require = 0, remap = false)
    private AEKey gtShanhai$matchPhysicalRemainder(IPatternDetails.IInput input, AEKey key,
            Operation<AEKey> original) {
        return GraphPresenceDispatchBridge.physicalRemainder(input, key, original::call);
    }
}
