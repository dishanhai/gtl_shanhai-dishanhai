package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai

import com.dishanhai.gt_shanhai.api.machine.part.IRecipeThreadSource;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

/** Resolves attached capacity without adding contributions from duplicate sources. */
public final class RecipeThreadSources {
    private RecipeThreadSources() {}

    public static long capacity(IRecipeLogicMachine machine, long intrinsic) {
        long capacity = Math.max(1L, intrinsic);
        if (machine instanceof IRecipeThreadSource source) {
            capacity = Math.max(capacity, Math.max(1L, source.getRecipeThreadCapacity()));
        }
        if (machine instanceof IMultiController controller && controller.isFormed()) {
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IRecipeThreadSource source) {
                    capacity = Math.max(capacity, Math.max(1L, source.getRecipeThreadCapacity()));
                }
            }
        }
        return capacity;
    }

    public static void changed(IMultiPart part) {
        for (IMultiController controller : part.getControllers()) {
            if (controller.isFormed() && controller instanceof IRecipeLogicMachine machine
                    && machine.getRecipeLogic() instanceof com.dishanhai.gt_shanhai.api.machine.IRecipeThreadDisplay display) {
                display.onThreadInputsChanged();
            }
        }
    }

}
