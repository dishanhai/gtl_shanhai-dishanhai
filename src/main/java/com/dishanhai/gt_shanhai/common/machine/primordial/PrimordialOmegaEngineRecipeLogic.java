package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import java.util.LinkedHashSet;
import java.util.Set;

public class PrimordialOmegaEngineRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {

    private static final long ACTIVE_LOOKUP_CACHE_TICKS = 100L;

    public PrimordialOmegaEngineRecipeLogic(SelectableRecipeTypeSetMachine machine) {
        super(machine);
    }

    @Override
    protected long getLookupCacheTicks() {
        return ACTIVE_LOOKUP_CACHE_TICKS;
    }

    @Override
    public int getMultipleThreads() {
        return Integer.MAX_VALUE;
    }

    /**
     * 主機也必須吃到已安裝模組提供的產出倍率。
     *
     * <p>倍率在候選配方階段套用，父類後續的並行上限、輸出容量檢查和無線配方建構
     * 才會全部以放大後的輸出為準；若在最終無線配方建立後才複製，會丟失
     * {@code WirelessGTRecipe} 的能源封裝。</p>
     */
    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        Set<GTRecipe> recipes = super.lookupRecipeIterator();
        if (recipes.isEmpty() || !(getMachine() instanceof PrimordialOmegaEngineMachine engine)) {
            return recipes;
        }
        int multiplier = engine.getMountedOutputMultiplier();
        if (multiplier <= 1) {
            return recipes;
        }

        Set<GTRecipe> amplified = new LinkedHashSet<>(recipes.size());
        for (GTRecipe recipe : recipes) {
            amplified.add(PrimordialRecipeOutputAmplifier.apply(recipe, multiplier));
        }
        return amplified;
    }
}
