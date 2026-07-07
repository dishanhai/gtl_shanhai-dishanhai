package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public class MEPatternBufferRecipeCacheRevisionMixin {

    @Unique
    private long gtShanhai$recipeCacheRevision = Long.MIN_VALUE;

    @Inject(method = "hasRecipeCacheInSlot", at = @At("HEAD"), remap = false, require = 0)
    private void gtShanhai$invalidateStaleRecipeCache(int slot, CallbackInfoReturnable<Boolean> cir) {
        long currentRevision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        if (gtShanhai$recipeCacheRevision == currentRevision) {
            return;
        }
        gtShanhai$recipeCacheRevision = currentRevision;
        DShanhaiRecipeModifierAPI.invalidatePatternCacheOwner(this, "revision-check:" + slot);
    }
}
