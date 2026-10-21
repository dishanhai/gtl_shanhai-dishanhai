package com.dishanhai.gt_shanhai.api;

import appeng.api.crafting.IPatternDetails;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeExecutionGuard;
import com.dishanhai.gt_shanhai.common.item.PatternSlotScopedRecipe;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.mixin.MEPatternBufferSlot2PatternAccessor;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * Shared pattern identity checks. Only the accessor interface is referenced here;
 * injected mixin implementation classes must not be loaded directly.
 */
public final class PatternIdentityGuard {

    private PatternIdentityGuard() {
    }

    public static boolean slotRejectsRecipe(MetaMachine handlerMachine, GTRecipe recipe, int trySlot) {
        if (PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)) {
            return false;
        }
        if (scopedSourceRejects(handlerMachine, recipe, trySlot)) {
            return true;
        }
        if (!(handlerMachine instanceof RecipeTypePatternBufferPartMachine buffer) || recipe == null) {
            return false;
        }
        if (buffer.gtShanhai$wildcardSlotRejectsRecipe(trySlot, recipe)) {
            return true;
        }
        if (PatternSlotScopedRecipe.isScoped(recipe)) {
            return false;
        }
        Int2ObjectMap<IPatternDetails> slot2Pattern =
                ((MEPatternBufferSlot2PatternAccessor) buffer).gtShanhai$getSlot2PatternMap();
        IPatternDetails pattern = slot2Pattern.get(trySlot);
        if (pattern == null) {
            return false;
        }
        return !PatternIdentityMatcher.matches(recipe, pattern);
    }

    public static boolean scopedSourceRejects(MetaMachine handlerMachine, GTRecipe recipe, int trySlot) {
        if (!(handlerMachine instanceof RecipeTypePatternBufferPartMachine) || recipe == null) {
            return false;
        }
        if (PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)) {
            return false;
        }
        var level = handlerMachine.getLevel();
        var dimensionId = level == null ? null : level.dimension().location();
        return !PatternSlotScopedRecipe.matchesSource(recipe, dimensionId, handlerMachine.getPos(), trySlot);
    }

}
