package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：JEI 搜尋索引保持原執行緒，避免長清單連續阻塞視窗。 */
@Mixin(targets = "mezz.jei.gui.ingredients.ListElementInfo", remap = false)
public abstract class ShanhaiLoadingJeiIndexMixin {
    @Inject(method = "create", at = @At("HEAD"), remap = false)
    private static void shanhai$betweenIngredients(CallbackInfoReturnable<?> cir) {
        ShanhaiLoadingClient.checkpoint("JEI 物品與搜尋索引 ...");
    }
}
