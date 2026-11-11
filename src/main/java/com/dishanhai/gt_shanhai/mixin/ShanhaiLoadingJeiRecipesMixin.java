package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：JEI 預覽建立保持主執行緒，在每筆配方間讓視窗回應。 */
@Mixin(targets = "mezz.jei.library.recipes.RecipeManagerInternal", remap = false)
public abstract class ShanhaiLoadingJeiRecipesMixin {
    @Inject(method = "addRecipe", at = @At("HEAD"), remap = false)
    private void shanhai$betweenRecipes(CallbackInfoReturnable<Boolean> cir) {
        ShanhaiLoadingClient.checkpoint(com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingText.text("jei.recipes"));
    }
}
