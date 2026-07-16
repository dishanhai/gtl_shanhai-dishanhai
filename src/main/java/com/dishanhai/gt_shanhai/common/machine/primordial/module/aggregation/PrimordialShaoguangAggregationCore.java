package com.dishanhai.gt_shanhai.common.machine.primordial.module.aggregation;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialShaoguangAggregationCore extends PrimordialParallelProcessingModuleBase {

    public PrimordialShaoguangAggregationCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialShaoguangAggregationCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialShaoguangAggregationCoreLogic(this);
    }

    @Override
    public PrimordialShaoguangAggregationCoreLogic getRecipeLogic() {
        return (PrimordialShaoguangAggregationCoreLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_shaoguang_aggregation_core.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_shaoguang_aggregation_core.mode";
    }
}
