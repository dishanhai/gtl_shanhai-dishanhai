package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import com.glodblock.github.extendedae.common.items.InfinityCell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InfinityCell.class, remap = false)
public class EaeInfinityCellRecordMixin {

    @Inject(method = "getRecord", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$normalizeEmptyFluidRecord(CallbackInfoReturnable<AEKey> cir) {
        AEKey key = cir.getReturnValue();
        if (key instanceof AEFluidKey fluidKey && fluidKey.hasTag() && fluidKey.getTag().isEmpty()) {
            cir.setReturnValue(fluidKey.dropSecondary());
        }
    }
}
