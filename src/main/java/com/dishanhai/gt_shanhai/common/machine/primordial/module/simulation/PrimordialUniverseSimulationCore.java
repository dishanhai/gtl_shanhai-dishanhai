package com.dishanhai.gt_shanhai.common.machine.primordial.module.simulation;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialUniverseSimulationCore extends PrimordialParallelProcessingModuleBase {

    public PrimordialUniverseSimulationCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialUniverseSimulationCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialUniverseSimulationCoreLogic(this);
    }

    @Override
    public PrimordialUniverseSimulationCoreLogic getRecipeLogic() {
        return (PrimordialUniverseSimulationCoreLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_universe_simulation_core.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_universe_simulation_core.mode";
    }
}
