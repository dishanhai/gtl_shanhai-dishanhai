package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.api.ae2.AeStorageAmountMath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 防止多个 AE 存储源合并 Long.MAX_VALUE 时发生 long 溢出。 */
@Mixin(targets = "appeng.me.storage.NetworkStorage", remap = false)
public abstract class NetworkStorageSaturatedAggregationMixin {

    @Unique
    private final KeyCounter gtShanhai$providerContribution = new KeyCounter();

    @Redirect(method = "getAvailableStacks", at = @At(value = "INVOKE",
            target = "Lappeng/api/storage/MEStorage;getAvailableStacks(Lappeng/api/stacks/KeyCounter;)V"), remap = false)
    private void gtShanhai$mergeProviderSaturated(MEStorage provider, KeyCounter output) {
        AeStorageAmountMath.getAvailableStacksSaturated(provider, output, this.gtShanhai$providerContribution);
    }
}
