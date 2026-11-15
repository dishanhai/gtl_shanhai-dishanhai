package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.GraphPresenceDispatchBridge;
import com.dishanhai.gt_shanhai.common.item.GraphVirtualPresence;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.cgse.core.GraphRecipe;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuHost;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlExecutionAdapter",
        priority = 1500, remap = false)
public abstract class GtlCoreGraphPresenceExecutionAdapterMixin {

    @Shadow @Final private GraphCpuHost host;
    @Shadow private IPatternDetails selectedPattern;
    @Shadow private String reason;

    @WrapOperation(method = "matches", at = @At(value = "INVOKE",
            target = "Lappeng/api/crafting/IPatternDetails$IInput;getMultiplier()J"), remap = false)
    private long gtShanhai$matchExternalPresence(IPatternDetails.IInput input, Operation<Long> original,
            IPatternDetails pattern, GraphRecipe<AEKey> recipe, boolean equivalentProcessing,
            @Local(ordinal = 0) int inputSlot) {
        return GraphVirtualPresence.isExternalInput(pattern, recipe, inputSlot) ? 0L : original.call(input);
    }

    @Inject(method = "capacity", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$recheckPresenceBeforeDispatch(GraphRecipe<AEKey> recipe, long requested,
            CallbackInfoReturnable<Long> cir) {
        if (cir.getReturnValue() <= 0L || this.selectedPattern == null) return;
        if (GraphVirtualPresence.missingExternalPresence(this.selectedPattern, recipe,
                cir.getReturnValue(), this.host.grid(), this.host.source()) != null) {
            this.reason = "MISSING_TOOL";
            cir.setReturnValue(0L);
        }
    }

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
