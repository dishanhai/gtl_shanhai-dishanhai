package com.dishanhai.gt_shanhai.util;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiSuperParallelCoreMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

public final class MolecularAssemblerHelper {

    private MolecularAssemblerHelper() {}

    public static boolean hasSuperParallelCore(IMultiController controller) {
        if (controller == null || !controller.isFormed()) return false;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof DShanhaiSuperParallelCoreMachine) return true;
        }
        return false;
    }
}
