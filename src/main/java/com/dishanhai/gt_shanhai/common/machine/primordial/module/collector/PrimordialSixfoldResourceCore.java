package com.dishanhai.gt_shanhai.common.machine.primordial.module.collector;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialSixfoldResourceCore extends PrimordialParallelProcessingModuleBase {

    public PrimordialSixfoldResourceCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialSixfoldResourceCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialSixfoldResourceCoreLogic(this);
    }

    @Override
    public PrimordialSixfoldResourceCoreLogic getRecipeLogic() {
        return (PrimordialSixfoldResourceCoreLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_sixfold_resource_core.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_sixfold_resource_core.mode";
    }
}
