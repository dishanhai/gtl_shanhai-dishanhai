package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.JEIRecipeCache;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 重定向 IRecipeRegistration.addRecipes，在 JEI 接收配方前应用剥离+替换。
 * 同时缓存已注册的包装器到 JEIRecipeCache，供 RecipeSyncPacket 刷新时隐藏旧条目。
 */
@Mixin(targets = "com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory", remap = false)
public class JEIRecipeListMixin {

    private static final Logger LOG = LoggerFactory.getLogger("JEIRecipeList");

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "registerRecipes", at = @At(value = "INVOKE",
            target = "Lmezz/jei/api/registration/IRecipeRegistration;addRecipes(Lmezz/jei/api/recipe/RecipeType;Ljava/util/List;)V"),
            remap = false)
    private static void gtShanhai$addRecipes(IRecipeRegistration reg, RecipeType type, List recipes) {
        if (recipes == null || recipes.isEmpty()) {
            JEIRecipeCache.put(type, Collections.emptyList());
            reg.addRecipes(type, recipes);
            return;
        }
        List<GTRecipeWrapper> wrappers = new ArrayList<>();
        for (Object obj : recipes) {
            if (obj instanceof GTRecipeWrapper wrapper) {
                GTRecipe copy = wrapper.recipe.copy();
                DShanhaiRecipeModifierAPI.applyStripByType(copy);
                DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
                wrappers.add(new GTRecipeWrapper(copy));
            }
        }
        LOG.info("[JEIRecipeList] {}: {} recipes, modified={}", type.getUid(), recipes.size(), !wrappers.equals(recipes));
        JEIRecipeCache.put(type, wrappers);
        reg.addRecipes(type, (List) wrappers);
    }
}
