package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 收束 GTCEu 直接按 ID 读取配方的路径：返回 lookup 中唯一的运行时版本，
 * 不修改 RecipeManager 中的原始源码配方。
 */
@Mixin(value = GTRecipeType.class, remap = false)
public abstract class GTRecipeTypeModifierMixin {

    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$resolveRuntimeRecipe(RecipeManager recipeManager, ResourceLocation id,
                                                  CallbackInfoReturnable<GTRecipe> cir) {
        GTRecipeType self = (GTRecipeType) (Object) this;
        if (self.registryName == null || id == null) return;
        String typeId = self.registryName.toString();
        if (!DShanhaiRecipeModifierAPI.hasRuntimeStripOrReplaceRules(typeId)
                && !DShanhaiRecipeModifierAPI.hasRuntimeDeleteRules(typeId)) return;

        GTRecipe runtime = DShanhaiRecipeModifierAPI.findLookupRecipeById(typeId, id.toString());
        if (runtime != null) {
            cir.setReturnValue(runtime);
        } else if (DShanhaiRecipeModifierAPI.hasRuntimeDeleteRules(typeId)) {
            cir.setReturnValue(null);
        }
    }
}
