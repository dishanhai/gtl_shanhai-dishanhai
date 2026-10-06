package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.GraphPresenceDispatchBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlDispatchPolicy",
        priority = 1500, remap = false)
public abstract class GtlCoreGraphPresenceDispatchPolicyMixin {

    @Inject(method = "configuration", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$presenceIsConfiguration(AEKey key,
            CallbackInfoReturnable<Boolean> cir) {
        if (GraphPresenceDispatchBridge.isPresenceKey(key)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "reusable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$presenceIsReusable(AEKey key,
            CallbackInfoReturnable<Boolean> cir) {
        if (GraphPresenceDispatchBridge.isPresenceKey(key)) {
            cir.setReturnValue(true);
        }
    }
}
