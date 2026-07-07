package com.dishanhai.gt_shanhai.common.machine.nebula;

import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/** 天界虹吸矩阵配方逻辑 — 4线程，并行由机器按流体等级决定 */
public class NebulaSiphonLogic extends GTLAddMultipleWirelessRecipesLogic {

    private static final Predicate<GTRecipe> MATCH_ALL = r -> true;
    private static final long LOOKUP_CACHE_TICKS = 20L;

    private GTRecipeType cachedRecipeType;
    private Set<GTRecipe> cachedRecipes;
    private long cachedRecipeTick = Long.MIN_VALUE;

    public NebulaSiphonLogic(NebulaSiphonMachine machine) {
        super(machine);
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        NebulaSiphonMachine machine = (NebulaSiphonMachine) getMachine();
        GTRecipeType recipeType = ProgrammableHatchPartMachine.getEffectiveRecipeType(machine, machine.getRecipeType());
        long tick = machine.getOffsetTimer();
        if (cachedRecipes != null && cachedRecipeType == recipeType
                && tick - cachedRecipeTick >= 0 && tick - cachedRecipeTick < LOOKUP_CACHE_TICKS) {
            return cachedRecipes;
        }

        Set<GTRecipe> result = new ObjectOpenHashSet<>();
        Iterator<GTRecipe> iter = recipeType.getLookup()
                .getRecipeIterator((IRecipeCapabilityHolder) getMachine(), MATCH_ALL);
        while (iter.hasNext()) {
            result.add(iter.next());
        }
        cachedRecipeType = recipeType;
        cachedRecipes = result;
        cachedRecipeTick = tick;
        return result;
    }

    @Override
    public int getMultipleThreads() {
        return 4;
    }
}
