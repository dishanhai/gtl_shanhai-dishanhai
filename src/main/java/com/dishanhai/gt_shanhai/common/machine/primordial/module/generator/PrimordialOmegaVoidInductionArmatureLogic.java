package com.dishanhai.gt_shanhai.common.machine.primordial.module.generator;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

public class PrimordialOmegaVoidInductionArmatureLogic extends PrimordialModuleRecipeLogic {
    public PrimordialOmegaVoidInductionArmatureLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine, logicMachine -> machine instanceof PrimordialOmegaVoidInductionArmature armature
                && armature.canStartGeneratingRecipe());
    }

    @Override
    protected boolean checkBeforeWorking() {
        return getMachine() instanceof PrimordialOmegaVoidInductionArmature armature
                && armature.canStartGeneratingRecipe();
    }

    @Override
    protected boolean tracksActualParallel() {
        return false; // 直接執行原配方，未建立原初無線批次。
    }

    @Override
    public GTRecipe getGTRecipe() {
        if (!isThreadRecipeLookupAllowed()) return null;
        if (containmentBonus().locked()) return null;
        if (!checkBeforeWorking()) return null;
        var recipes = lookupRecipeIterator();
        if (recipes == null || recipes.isEmpty()) return null;
        for (GTRecipe recipe : recipes) {
            if (recipe != null) return recipe;
        }
        return null;
    }
}
