package com.dishanhai.gt_shanhai.common.machine.primordial.module.matter;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
public class PrimordialSupercriticalMatterGenerationCore extends PrimordialParallelProcessingModuleBase {
    public PrimordialSupercriticalMatterGenerationCore(IMachineBlockEntity holder, Object... args) { super(holder, args); }
    @Override public PrimordialSupercriticalMatterGenerationCoreLogic createRecipeLogic(Object... args) { return new PrimordialSupercriticalMatterGenerationCoreLogic(this); }
    @Override public PrimordialSupercriticalMatterGenerationCoreLogic getRecipeLogic() { return (PrimordialSupercriticalMatterGenerationCoreLogic) recipeLogic; }
    @Override protected String getMachineNameKey() { return "gt_shanhai.machine.primordial_supercritical_matter_generation_core.name"; }
    @Override protected String getMachineModeKey() { return "gt_shanhai.machine.primordial_supercritical_matter_generation_core.mode"; }
}
