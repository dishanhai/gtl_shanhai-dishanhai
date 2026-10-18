package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.recipe.PrimordialReverseEntropyRecipes;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 数据包 apply 结束时生成逆熵构成。优先级低于 GTCEu 的同位置注入，
 * 这样分子解构已经进表；本注入同时写配方表和查找表，先后顺序都能留下配方。
 */
@Mixin(value = RecipeManager.class, priority = 900)
public class ReverseEntropyRecipeMixin {

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL"))
    private void gtShanhai$installReverseEntropyRecipes(CallbackInfo ci) {
        PrimordialReverseEntropyRecipes.install((RecipeManager) (Object) this);
    }
}
