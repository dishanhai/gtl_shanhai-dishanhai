package com.dishanhai.gt_shanhai.common.machine.primordial.module.matrix;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialQuantumDistortionMatrix extends PrimordialParallelProcessingModuleBase {

    public PrimordialQuantumDistortionMatrix(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialQuantumDistortionMatrixLogic createRecipeLogic(Object... args) {
        return new PrimordialQuantumDistortionMatrixLogic(this);
    }

    @Override
    public PrimordialQuantumDistortionMatrixLogic getRecipeLogic() {
        return (PrimordialQuantumDistortionMatrixLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_quantum_distortion_matrix.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_quantum_distortion_matrix.mode";
    }
}
