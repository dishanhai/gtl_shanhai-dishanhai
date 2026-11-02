package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：逐物品索引詞抽取，同時覆蓋 JEI 及 JEI Optimizer 的主執行緒 tooltip 路徑。 */
@Mixin(targets = "mezz.jei.common.search.PrefixInfo", remap = false)
public abstract class ShanhaiLoadingJeiSearchMixin {
    @Inject(method = "getStrings", at = @At("HEAD"), remap = false)
    private void shanhai$searchCheckpoint(CallbackInfoReturnable<?> cir) {
        ShanhaiLoadingClient.checkpoint("JEI 搜尋文字索引 ...");
    }
}
