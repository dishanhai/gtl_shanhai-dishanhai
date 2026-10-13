package com.dishanhai.gt_shanhai.common.machine.primordial.module.collector;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialDeepSpaceExtractionCore extends PrimordialParallelProcessingModuleBase {

    public PrimordialDeepSpaceExtractionCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialDeepSpaceExtractionCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialDeepSpaceExtractionCoreLogic(this);
    }

    @Override
    public PrimordialDeepSpaceExtractionCoreLogic getRecipeLogic() {
        return (PrimordialDeepSpaceExtractionCoreLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_deep_space_extraction_core.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_deep_space_extraction_core.mode";
    }
}
