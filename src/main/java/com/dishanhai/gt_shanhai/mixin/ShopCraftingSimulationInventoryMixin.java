package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraftActionSource;
import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraftAmounts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only dishanhai shop calculations exclude stock reserved for earlier plans. */
@Mixin(value = NetworkCraftingSimulationState.class, priority = 1500, remap = false)
public abstract class ShopCraftingSimulationInventoryMixin {
    @Unique
    private IStorageService gtShanhai$storage;
    @Unique
    private ShopAutoCraftActionSource gtShanhai$shopSource;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtShanhai$reserveShopStock(IStorageService storage, IActionSource source, CallbackInfo ci) {
        if (!(source instanceof ShopAutoCraftActionSource shopSource)) return;
        gtShanhai$storage = storage;
        gtShanhai$shopSource = shopSource;
    }

    @Inject(method = "simulateExtractParent", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$limitReservedStock(AEKey key, long amount, CallbackInfoReturnable<Long> cir) {
        if (gtShanhai$shopSource == null) return;
        long reserved = gtShanhai$shopSource.reserved().getOrDefault(key, 0L);
        if (reserved <= 0L) return;
        // GTLCore skips the native list; filter lazy reads without modifying its shared cache.
        long available = gtShanhai$storage.getInventory().extract(
                key, Long.MAX_VALUE, Actionable.SIMULATE, gtShanhai$shopSource);
        cir.setReturnValue(Math.min(cir.getReturnValue(),
                ShopAutoCraftAmounts.remainingStock(available, reserved)));
    }
}
