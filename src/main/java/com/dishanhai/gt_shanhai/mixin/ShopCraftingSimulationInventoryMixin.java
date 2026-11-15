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

/** 山海商店的合成模拟使用可提取库存，并扣除前序方案的预留。 */
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
        long simulated = cir.getReturnValue();
        if (gtShanhai$shopSource == null || simulated <= 0L) return;
        long reserved = gtShanhai$shopSource.reserved().getOrDefault(key, 0L);
        // AE2/GTLCore 可能使用缓存数量，确认方案时则按实际可提取量校验。
        long available = gtShanhai$storage.getInventory().extract(
                key, Long.MAX_VALUE, Actionable.SIMULATE, gtShanhai$shopSource);
        cir.setReturnValue(Math.min(simulated, ShopAutoCraftAmounts.remainingStock(available, reserved)));
    }
}
