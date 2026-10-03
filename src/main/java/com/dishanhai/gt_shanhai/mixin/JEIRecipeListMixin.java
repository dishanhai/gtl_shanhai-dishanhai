package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.JEIRecipeCache;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.RecipeManagerInternal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 JEI 最終收集入口處處理 GT 配方。
 *
 * GTLCore 的優化 mixin 會在 GTRecipeTypeCategory.registerRecipes 內直接呼叫
 * IRecipeRegistration.addRecipes；攔截上游 category 會同時撞上原生與優化路徑。
 * 在 RecipeManagerInternal 收口後，只保留同一配方 ID 的一份，並只對有規則的
 * GT 配方建立修改副本；普通 JEI 類型完全不經過這裡的規則邏輯。
 */
@Mixin(value = RecipeManagerInternal.class, remap = false)
public class JEIRecipeListMixin {

    private static final ThreadLocal<Boolean> REENTRANT = ThreadLocal.withInitial(() -> false);

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "addRecipes", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$normalizeGtRecipes(RecipeType type, List recipes, CallbackInfo ci) {
        if (REENTRANT.get() || recipes == null || recipes.isEmpty()) return;

        boolean hasGtWrapper = false;
        boolean allGtWrappers = true;
        boolean changed = false;
        List normalized = new ArrayList(recipes.size());
        for (Object value : recipes) {
            if (!(value instanceof GTRecipeWrapper wrapper)) {
                allGtWrappers = false;
                normalized.add(value);
                continue;
            }
            hasGtWrapper = true;
            GTRecipe recipe = wrapper.recipe;
            if (recipe == null) {
                normalized.add(value);
                continue;
            }
            String typeId = recipe.recipeType == null || recipe.recipeType.registryName == null
                    ? "" : recipe.recipeType.registryName.toString();
            if (DShanhaiRecipeModifierAPI.isDeletedByRuntimeRule(typeId, recipe)) {
                changed = true;
                continue;
            }
            GTRecipe prepared = DShanhaiRecipeModifierAPI.prepareJeiRecipe(recipe);
            if (prepared != recipe) {
                normalized.add(new GTRecipeWrapper(prepared));
                changed = true;
            } else {
                normalized.add(value);
            }
        }
        if (!hasGtWrapper) return;

        // GTCEu registration lists are homogeneous. Keep mixed lists untouched except
        // for a required deletion/rewrite, since JEI may contain another recipe type.
        if (!allGtWrappers) {
            if (!changed) return;
            gtShanhai$reenter(type, normalized, ci);
            return;
        }

        List unseen = JEIRecipeCache.filterUnseen(type, normalized);
        JEIRecipeCache.append(type, unseen);
        if (!changed && unseen.size() == recipes.size() && gtShanhai$sameEntries(recipes, unseen)) {
            return;
        }
        gtShanhai$reenter(type, unseen, ci);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void gtShanhai$reenter(RecipeType type, List recipes, CallbackInfo ci) {
        REENTRANT.set(true);
        try {
            if (recipes.isEmpty()) {
                ci.cancel();
                return;
            }
            ((RecipeManagerInternal) (Object) this).addRecipes(type, recipes);
            ci.cancel();
        } finally {
            REENTRANT.set(false);
        }
    }

    private static boolean gtShanhai$sameEntries(List<?> left, List<?> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i) != right.get(i)) return false;
        }
        return true;
    }
}
