package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

/** Cloud allocation must never broaden a player's current recipe type selection. */
public final class EnhancedCorePrimordialRecipeTypes {
    private EnhancedCorePrimordialRecipeTypes() {}

    public static GTRecipeType[] selected(WorkableElectricMultiblockMachine machine,
                                         MultiblockMachineDefinition definition) {
        if (machine instanceof SelectableRecipeTypeSetMachine selectable) {
            return selectable.getSelectedRecipeTypes();
        }
        return definition.getRecipeTypes();
    }
}
