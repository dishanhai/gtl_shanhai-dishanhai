package com.dishanhai.gt_shanhai.common.machine.stripping;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes;

import org.gtlcore.gtlcore.common.data.GTLRecipeTypes;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 世线剥离震荡发生器配方逻辑
 * 搜索宇宙模拟与星核剥离两种配方类型，从世线震荡中提取能量
 */
public class WorldLineStrippingOscillationGeneratorLogic extends GTLAddMultipleWirelessRecipesLogic {

    private static final GTRecipeType[] ALL_TYPES;
    private static final long LOOKUP_CACHE_TICKS = 20L;

    static {
        var types = new java.util.ArrayList<GTRecipeType>();
        types.add(GTLRecipeTypes.COSMOS_SIMULATION_RECIPES);
        types.add(GTLAddRecipesTypes.INSTANCE.getSTAR_CORE_STRIPPER());
        if (net.minecraftforge.fml.ModList.get().isLoaded("gtl_extend")) {
            // 全量扫描 GTRegistries.RECIPE_TYPES 寻找 horizon_matter_decompression
            for (GTRecipeType t : com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES) {
                if ("gtlcore:horizon_matter_decompression".equals(t.registryName.toString())) {
                    types.add(t);
                    break;
                }
            }
        }
        ALL_TYPES = types.toArray(new GTRecipeType[0]);
    }

    private static final Predicate<GTRecipe> MATCH_ALL = r -> true;

    private Set<GTRecipe> cachedRecipes;
    private long cachedRecipeTick = Long.MIN_VALUE;

    public WorldLineStrippingOscillationGeneratorLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }

    @Override
    public double getTotalEuOfRecipe(GTRecipe recipe) {
        return super.getTotalEuOfRecipe(recipe);
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        if (isLock()) {
            if (getLockRecipe() == null) {
                for (GTRecipeType type : ALL_TYPES) {
                    GTRecipe recipe = type.getLookup().find(
                            (IRecipeCapabilityHolder) getMachine(), MATCH_ALL);
                    if (recipe != null) {
                        setLockRecipe(recipe);
                        break;
                    }
                }
            } else {
                if (!checkRecipe(getLockRecipe())) {
                    return Collections.emptySet();
                }
            }
            GTRecipe locked = getLockRecipe();
            if (locked == null) return Collections.emptySet();
            return Collections.singleton(locked);
        }

        long tick = getMachine().getOffsetTimer();
        if (cachedRecipes != null && tick - cachedRecipeTick >= 0
                && tick - cachedRecipeTick < LOOKUP_CACHE_TICKS) {
            return cachedRecipes;
        }

        Set<GTRecipe> result = new ObjectOpenHashSet<>();
        for (GTRecipeType type : ALL_TYPES) {
            Iterator<GTRecipe> iter = type.getLookup()
                    .getRecipeIterator((IRecipeCapabilityHolder) getMachine(), MATCH_ALL);
            while (iter.hasNext()) {
                result.add(iter.next());
            }
        }
        cachedRecipes = result;
        cachedRecipeTick = tick;
        return result;
    }
}
