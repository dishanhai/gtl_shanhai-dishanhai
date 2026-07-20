package com.dishanhai.gt_shanhai.api;

import appeng.api.crafting.IPatternDetails;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeExecutionGuard;
import com.dishanhai.gt_shanhai.common.item.PatternSlotScopedRecipe;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

import java.lang.reflect.Method;

/**
 * Non-mixin helper for pattern identity guards. Mixin classes must not be referenced
 * directly from injected runtime code, so shared logic lives outside the mixin package.
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
        if (PatternSlotScopedRecipe.isScoped(recipe)) {
            return false;
        }
        if (!(handlerMachine instanceof RecipeTypePatternBufferPartMachine)) {
            return false;
        }
        if (!(handlerMachine instanceof MEPatternBufferPartMachine buffer)) {
            return false;
        }
        if (recipe == null) {
            return false;
        }
        Int2ObjectMap<IPatternDetails> slot2Pattern = getSlot2PatternMap(buffer);
        if (slot2Pattern == null) {
            return false;
        }
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

    @SuppressWarnings("unchecked")
    private static Int2ObjectMap<IPatternDetails> getSlot2PatternMap(MEPatternBufferPartMachine buffer) {
        try {
            Method method = findMethod(buffer.getClass(), "gtShanhai$getSlot2PatternMap");
            Object value = method.invoke(buffer);
            if (value instanceof Int2ObjectMap<?>) {
                return (Int2ObjectMap<IPatternDetails>) value;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }
}
