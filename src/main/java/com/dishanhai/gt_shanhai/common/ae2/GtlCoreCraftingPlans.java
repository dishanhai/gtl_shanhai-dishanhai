package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.networking.crafting.ICraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuController;

public final class GtlCoreCraftingPlans {

    public static boolean isGraphPlan(ICraftingPlan plan) {
        try {
            return GraphCpuController.isGraphPlan(plan);
        } catch (NoClassDefFoundError unavailable) {
            // Older GTLCore releases only support the tree engine.
            if (!"org/gtlcore/gtlcore/integration/ae2/graph/GraphCpuController".equals(unavailable.getMessage())) {
                throw unavailable;
            }
            return false;
        }
    }

    private GtlCoreCraftingPlans() {}
}
