package com.dishanhai.gt_shanhai.common.machine.primordial.module.matrix;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialWorldlineTraversalMatrix extends PrimordialParallelProcessingModuleBase {

    public PrimordialWorldlineTraversalMatrix(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialWorldlineTraversalMatrixLogic createRecipeLogic(Object... args) {
        return new PrimordialWorldlineTraversalMatrixLogic(this);
    }

    @Override
    public PrimordialWorldlineTraversalMatrixLogic getRecipeLogic() {
        return (PrimordialWorldlineTraversalMatrixLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_worldline_traversal_matrix.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_worldline_traversal_matrix.mode";
    }
}
