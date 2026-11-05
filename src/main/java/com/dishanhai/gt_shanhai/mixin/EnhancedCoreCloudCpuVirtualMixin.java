package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudInputsAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** 山海：補料與批次容量共用同一份實料需求，不抽取在場輸入。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudCpuRefill", remap = false)
public abstract class EnhancedCoreCloudCpuVirtualMixin {

    @Inject(method = "physicalUnits", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$onlyConsumedInputs(IPatternDetails pattern,
            CallbackInfoReturnable<Map<AEKey, Long>> cir) {
        if (VirtualPatternEncodingHelper.containsVirtualProviderPattern(pattern)) {
            cir.setReturnValue(EnhancedCoreCloudInputsAdapter.physicalUnits(pattern));
        }
    }
}
