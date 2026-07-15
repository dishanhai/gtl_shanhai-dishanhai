package com.dishanhai.gt_shanhai.common.machine.primordial.module.rift;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
public class PrimordialMolecularRiftCore extends PrimordialParallelProcessingModuleBase {
    public PrimordialMolecularRiftCore(IMachineBlockEntity holder, Object... args) { super(holder, args); }
    @Override public PrimordialMolecularRiftCoreLogic createRecipeLogic(Object... args) { return new PrimordialMolecularRiftCoreLogic(this); }
    @Override public PrimordialMolecularRiftCoreLogic getRecipeLogic() { return (PrimordialMolecularRiftCoreLogic) recipeLogic; }
    @Override protected String getMachineNameKey() { return "gt_shanhai.machine.primordial_molecular_rift_core.name"; }
    @Override protected String getMachineModeKey() { return "gt_shanhai.machine.primordial_molecular_rift_core.mode"; }
}
