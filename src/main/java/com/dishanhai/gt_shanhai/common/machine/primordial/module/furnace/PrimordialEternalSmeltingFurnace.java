package com.dishanhai.gt_shanhai.common.machine.primordial.module.furnace;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialEternalSmeltingFurnace extends PrimordialParallelProcessingModuleBase {

    public PrimordialEternalSmeltingFurnace(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialEternalSmeltingFurnaceLogic createRecipeLogic(Object... args) {
        return new PrimordialEternalSmeltingFurnaceLogic(this);
    }

    @Override
    public PrimordialEternalSmeltingFurnaceLogic getRecipeLogic() {
        return (PrimordialEternalSmeltingFurnaceLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_eternal_smelting_furnace.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_eternal_smelting_furnace.mode";
    }
}
