package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：Jech／其他搜尋庫仍透過 JEI 適配器逐字串建立索引。 */
@Mixin(targets = "mezz.jei.common.search.SearchStorageBuilderAdapter", remap = false)
public abstract class ShanhaiLoadingJeiSearchStorageMixin {
    @Inject(method = "put(Ljava/lang/String;Ljava/lang/Object;)V", at = @At("HEAD"), remap = false)
    private void shanhai$storageCheckpoint(CallbackInfo ci) {
        ShanhaiLoadingClient.checkpoint("JEI 搜尋結構索引 ...");
    }
}
