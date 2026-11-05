package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudInputsAdapter;
import com.gtl.enhancedcore.common.cloud.CloudPatternMatching;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** 山海：雲端匹配沿用有限的物品／流體在場需求。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudPatternMatching", remap = false)
public abstract class EnhancedCoreCloudProbeVirtualMixin {

    @Inject(method = "probe", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$splitPresence(IPatternDetails pattern, Map<AEKey, Long> virtual,
            CallbackInfoReturnable<CloudPatternMatching.Probe> cir) {
        if (VirtualPatternEncodingHelper.containsVirtualProviderPattern(pattern)) {
            cir.setReturnValue(EnhancedCoreCloudInputsAdapter.probe(pattern, virtual));
        }
    }
}
