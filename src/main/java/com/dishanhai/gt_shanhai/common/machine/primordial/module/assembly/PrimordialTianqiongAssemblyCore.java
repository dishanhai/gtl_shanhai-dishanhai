package com.dishanhai.gt_shanhai.common.machine.primordial.module.assembly;

import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

public class PrimordialTianqiongAssemblyCore extends PrimordialParallelProcessingModuleBase {

    public PrimordialTianqiongAssemblyCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialTianqiongAssemblyCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialTianqiongAssemblyCoreLogic(this);
    }

    @Override
    public PrimordialTianqiongAssemblyCoreLogic getRecipeLogic() {
        return (PrimordialTianqiongAssemblyCoreLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_tianqiong_assembly_core.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_tianqiong_assembly_core.mode";
    }
}
