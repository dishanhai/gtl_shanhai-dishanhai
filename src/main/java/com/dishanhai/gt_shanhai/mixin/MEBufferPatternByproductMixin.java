package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.pattern.AEProcessingPattern;

import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 樣板總成預設裁掉副產物後再掛上網路。有標識的樣板改保留全部產出。
 */
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.handler.MEBufferPatternHelper", remap = false)
public class MEBufferPatternByproductMixin {

    @Inject(method = "createPatternWithoutCircuit", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$keepMarkedByproducts(AEProcessingPattern pattern, Level level, boolean keepByProduct,
            CallbackInfoReturnable<IPatternDetails> cir) {
        IPatternDetails restored = PatternByproductOrder.withByproductOutputs(pattern, level);
        if (restored != null) cir.setReturnValue(restored);
    }
}
