package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public class MEPatternBufferCacheInvalidationMixin {

    @Inject(method = "onLoad", at = @At("TAIL"), remap = false)
    private void gtShanhai$registerRecipeModifierCacheOwner(CallbackInfo ci) {
        DShanhaiRecipeModifierAPI.registerPatternCacheOwner(this);
    }

    @Inject(method = "onUnload", at = @At("HEAD"), remap = false, require = 0)
    private void gtShanhai$unregisterRecipeModifierCacheOwner(CallbackInfo ci) {
        DShanhaiRecipeModifierAPI.unregisterPatternCacheOwner(this);
    }
}
