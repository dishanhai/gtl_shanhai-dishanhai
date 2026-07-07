package com.dishanhai.gt_shanhai.common.util;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

public final class HubMachineHelper {

    private HubMachineHelper() {}

    public static boolean hasHub(IMultiController controller) {
        try {
            if (controller == null || !controller.isFormed()) {
                return false;
            }
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
