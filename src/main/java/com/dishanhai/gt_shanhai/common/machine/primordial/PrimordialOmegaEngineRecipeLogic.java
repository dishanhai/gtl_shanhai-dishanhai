package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;

public class PrimordialOmegaEngineRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {

    public PrimordialOmegaEngineRecipeLogic(SelectableRecipeTypeSetMachine machine) {
        super(machine);
    }

    @Override
    public int getMultipleThreads() {
        return Integer.MAX_VALUE;
    }
}
