package com.dishanhai.gt_shanhai.common.machine.primordial.module.reactor;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
public class PrimordialCosmicReactor extends PrimordialParallelProcessingModuleBase {
    public PrimordialCosmicReactor(IMachineBlockEntity holder, Object... args) { super(holder, args); }
    @Override public PrimordialCosmicReactorLogic createRecipeLogic(Object... args) { return new PrimordialCosmicReactorLogic(this); }
    @Override public PrimordialCosmicReactorLogic getRecipeLogic() { return (PrimordialCosmicReactorLogic) recipeLogic; }
    @Override protected String getMachineNameKey() { return "gt_shanhai.machine.primordial_cosmic_reactor.name"; }
    @Override protected String getMachineModeKey() { return "gt_shanhai.machine.primordial_cosmic_reactor.mode"; }
}
