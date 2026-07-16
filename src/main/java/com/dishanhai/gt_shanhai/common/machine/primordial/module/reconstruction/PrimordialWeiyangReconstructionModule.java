package com.dishanhai.gt_shanhai.common.machine.primordial.module.reconstruction;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialWeiyangReconstructionModule extends PrimordialParallelProcessingModuleBase {

    public PrimordialWeiyangReconstructionModule(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialWeiyangReconstructionModuleLogic createRecipeLogic(Object... args) {
        return new PrimordialWeiyangReconstructionModuleLogic(this);
    }

    @Override
    public PrimordialWeiyangReconstructionModuleLogic getRecipeLogic() {
        return (PrimordialWeiyangReconstructionModuleLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_weiyang_reconstruction_module.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_weiyang_reconstruction_module.mode";
    }
}
