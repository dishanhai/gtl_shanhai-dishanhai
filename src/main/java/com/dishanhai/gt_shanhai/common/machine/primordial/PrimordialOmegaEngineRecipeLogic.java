package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;

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
}
