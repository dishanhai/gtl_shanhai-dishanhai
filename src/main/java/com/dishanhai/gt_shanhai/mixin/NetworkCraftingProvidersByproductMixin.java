package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.me.service.helpers.NetworkCraftingProviders;

import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * AE 掛上樣板供應器時只把主產物放進可合成索引。
 * 帶 {@code gt_shanhai_order_byproducts} 的樣板在這裡補登記其餘產出。
 */
@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders$ProviderState", remap = false)
public class NetworkCraftingProvidersByproductMixin {

    @Shadow(remap = false)
    private List<IPatternDetails> patterns;

    @Inject(method = "mount", at = @At("RETURN"), remap = false)
    private void gtShanhai$mountByproductOrders(NetworkCraftingProviders methods, CallbackInfo ci) {
        PatternByproductOrder.index(methods, this.patterns, this, true);
    }

    @Inject(method = "unmount", at = @At("RETURN"), remap = false)
    private void gtShanhai$unmountByproductOrders(NetworkCraftingProviders methods, CallbackInfo ci) {
        PatternByproductOrder.index(methods, this.patterns, this, false);
    }
}
