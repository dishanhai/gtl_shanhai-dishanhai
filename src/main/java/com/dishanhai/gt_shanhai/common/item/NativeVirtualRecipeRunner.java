package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import java.util.Set;

/**
 * 自订配方逻辑（EnhancedCore {@code RetryableRecipeLogic} 等）重写了
 * {@code findAndHandleRecipe} 且不调用 super，星律挂在 {@code RecipeLogic} 上的虚拟执行链进不去。
 * 这些机器还会用 {@code recipe.recipeType != getRecipeType()} 丢掉当前没选中的类型。
 * 这里只补宿主拥有、但当前没选中的样板，并走该机器自己的准入（电量、溢出、输出空间）。
 * 不打开作弊破限。
 */
public final class NativeVirtualRecipeRunner {

    private NativeVirtualRecipeRunner() {}

    public static boolean tryStartOwnedInactive(RecipeLogic logic) {
        if (logic == null || !(logic.machine instanceof IMultiController)) return false;
        if (logic.machine instanceof SelectableRecipeTypeSetMachine) return false;
        Set<GTRecipe> recipes = RecipeTypePatternSearchHelper.collectNativeVirtualRecipes(logic.machine);
        if (recipes.isEmpty()) return false;
        for (GTRecipe recipe : recipes) {
            GTRecipeType ownedInactive = ownedInactiveType(logic.machine, recipe);
            if (ownedInactive == null) continue;
            NativeVirtualSetupState.beginOwnedInactiveType(ownedInactive);
            NativeVirtualSetupState.beginOwnedInactiveCandidate(recipe);
            try {
                if (logic.checkMatchedRecipeAvailable(recipe)) return true;
            } finally {
                NativeVirtualSetupState.endOwnedInactiveCandidate();
                NativeVirtualSetupState.endOwnedInactiveType();
            }
        }
        return false;
    }

    static GTRecipeType ownedInactiveType(IRecipeLogicMachine machine, GTRecipe recipe) {
        if (machine == null || recipe == null || recipe.recipeType == null) return null;
        if (machine instanceof SelectableRecipeTypeSetMachine) return null;
        GTRecipeType[] types = machine.getRecipeTypes();
        if (types == null || types.length < 2) return null;
        int activeIndex = machine.getActiveRecipeType();
        GTRecipeType active = activeIndex >= 0 && activeIndex < types.length ? types[activeIndex] : null;
        if (sameRecipeType(active, recipe.recipeType)) return null;
        for (int i = 0; i < types.length; i++) {
            GTRecipeType type = types[i];
            if (sameRecipeType(type, recipe.recipeType)) return type;
        }
        return null;
    }

    public static boolean sameRecipeType(GTRecipeType left, GTRecipeType right) {
        if (left == right) return true;
        return left != null && right != null && left.registryName != null
                && left.registryName.equals(right.registryName);
    }
}
