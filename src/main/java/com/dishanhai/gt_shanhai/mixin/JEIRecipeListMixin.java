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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重定向 IRecipeRegistration.addRecipes，在 JEI 接收配方前应用剥离+替换。
 * 同时缓存已注册的包装器到 JEIRecipeCache，供 RecipeSyncPacket 刷新时隐藏旧条目。
 */
// Apply after GTLCore's optimized registration mixin so its injected addRecipes calls are covered too.
@Mixin(targets = "com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory", priority = 900, remap = false)
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
        Map<String, GTRecipeWrapper> wrappersById = new LinkedHashMap<>();
        for (Object obj : recipes) {
            if (obj instanceof GTRecipeWrapper wrapper) {
                GTRecipe copy = wrapper.recipe.copy();
                String typeId = copy.recipeType == null || copy.recipeType.registryName == null
                        ? "" : copy.recipeType.registryName.toString();
                if (DShanhaiRecipeModifierAPI.isDeletedByRuntimeRule(typeId, copy)) continue;
                DShanhaiRecipeModifierAPI.applyStripByType(copy);
                DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
                GTRecipeWrapper modified = new GTRecipeWrapper(copy);
                if (copy.getId() != null) {
                    wrappersById.put(copy.getId().toString(), modified);
                } else {
                    wrappers.add(modified);
                }
            }
        }
        wrappers.addAll(wrappersById.values());
        List<GTRecipeWrapper> unseen = JEIRecipeCache.filterUnseen(type, wrappers);
        JEIRecipeCache.append(type, unseen);
        LOG.info("[JEIRecipeList] {}: 输入={}, 规范化={}, 新增={}, 去重={}",
                type.getUid(), recipes.size(), wrappers.size(), unseen.size(), wrappers.size() - unseen.size());
        reg.addRecipes(type, (List) unseen);
    }
}
