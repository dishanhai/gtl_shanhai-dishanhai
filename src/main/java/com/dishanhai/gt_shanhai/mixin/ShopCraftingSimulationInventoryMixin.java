package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraftActionSource;
import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraftAmounts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/** Shop plans read a frozen local inventory, including when GTLCore replaces AE's native copy. */
@Mixin(value = NetworkCraftingSimulationState.class, priority = 1500, remap = false)
public abstract class ShopCraftingSimulationInventoryMixin {
    @Unique
    private KeyCounter gtShanhai$shopStock;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtShanhai$reserveShopStock(IStorageService storage, IActionSource source, CallbackInfo ci) {
        if (!(source instanceof ShopAutoCraftActionSource shopSource)) return;
        gtShanhai$shopStock = new KeyCounter();
        shopSource.stockSnapshot(storage).forEach((key, available) -> {
            long remaining = ShopAutoCraftAmounts.remainingStock(available, shopSource.reserved().getOrDefault(key, 0L));
            if (remaining > 0) gtShanhai$shopStock.add(key, remaining);
        });
    }

    // HEAD is intentional: bypass GTLCore's lazy live storage reads for shop jobs only.
    // Neither exact extraction nor fuzzy search may access the world on AE's worker thread.
    @Inject(method = "simulateExtractParent", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$limitReservedStock(AEKey key, long amount, CallbackInfoReturnable<Long> cir) {
        if (gtShanhai$shopStock != null) cir.setReturnValue(Math.min(gtShanhai$shopStock.get(key), amount));
    }

    @Inject(method = "findFuzzyParent", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$frozenFuzzyStock(AEKey key, CallbackInfoReturnable<Iterable<AEKey>> cir) {
        if (gtShanhai$shopStock == null) return;
        var keys = new ArrayList<AEKey>();
        for (var entry : gtShanhai$shopStock.findFuzzy(key, FuzzyMode.IGNORE_ALL)) keys.add(entry.getKey());
        cir.setReturnValue(keys);
    }
}
