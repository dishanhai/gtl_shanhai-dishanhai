package com.dishanhai.gt_shanhai.common.machine.misc;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SeventyTwoChangesMachine extends SimpleTieredMachine {

    public SeventyTwoChangesMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, 0, tier -> 1L, args);
    }

    @Override
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new ChaosRecipeLogic(this);
    }

    /**
     * 混沌配方逻辑：
     * - 普通配方类型：正常搜索
     * - 混沌合成：遍历全部配方类型，返回所有匹配结果
     */
    private static class ChaosRecipeLogic extends RecipeLogic {

        public ChaosRecipeLogic(SeventyTwoChangesMachine machine) {
            super(machine);
        }

        @Override
        protected Iterator<GTRecipe> searchRecipe() {
            var machine = (SeventyTwoChangesMachine) getMachine();
            var currentType = machine.recipeTypes[machine.activeRecipeType];

            // 非混沌模式 → 正常搜索
            if (currentType != DShanhaiRecipeTypes.CHAOS_CRAFTING) {
                return super.searchRecipe();
            }

            // 混沌模式 → 遍历全部配方类型收集匹配
            List<GTRecipe> matches = new ArrayList<>();
            for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
                if (type == DShanhaiRecipeTypes.CHAOS_CRAFTING) continue;
                try {
                    var it = type.searchRecipe((IRecipeCapabilityHolder) machine);
                    if (it != null && it.hasNext()) {
                        matches.add(it.next());
                    }
                } catch (Exception ignored) {}
            }
            return matches.iterator();
        }
    }
}
