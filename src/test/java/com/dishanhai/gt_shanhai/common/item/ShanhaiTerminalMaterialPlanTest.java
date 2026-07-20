package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalMaterialPlan;

class ShanhaiTerminalMaterialPlanTest {

    @Test
    void duplicateRequirementsAreMergedWithoutOverflow() {
        ShanhaiTerminalMaterialPlan plan = new ShanhaiTerminalMaterialPlan();

        plan.require("gtceu:cupronickel_coil_block", 8);
        plan.require("gtceu:cupronickel_coil_block", 12);
        plan.require("gtceu:invar_frame", Long.MAX_VALUE);
        plan.require("gtceu:invar_frame", 4);

        assertEquals(20, plan.required().get("gtceu:cupronickel_coil_block"));
        assertEquals(Long.MAX_VALUE, plan.required().get("gtceu:invar_frame"));
    }

    @Test
    void allocationUsesPlayerThenAeThenCrafting() {
        ShanhaiTerminalMaterialPlan plan = new ShanhaiTerminalMaterialPlan();
        plan.require("gtceu:kanthal_coil_block", 20);

        Map<String, ShanhaiTerminalMaterialPlan.Allocation> result = plan.allocate(
                Map.of("gtceu:kanthal_coil_block", 3L),
                Map.of("gtceu:kanthal_coil_block", 5L));

        ShanhaiTerminalMaterialPlan.Allocation allocation = result.get("gtceu:kanthal_coil_block");
        assertEquals(3, allocation.fromPlayer());
        assertEquals(5, allocation.fromAe());
        assertEquals(12, allocation.toCraft());
    }

    @Test
    void extraStockNeverCreatesNegativeShortage() {
        ShanhaiTerminalMaterialPlan plan = new ShanhaiTerminalMaterialPlan();
        plan.require("gtceu:steel_frame", 2);

        ShanhaiTerminalMaterialPlan.Allocation allocation = plan.allocate(
                Map.of("gtceu:steel_frame", 9L),
                Map.of("gtceu:steel_frame", 9L)).get("gtceu:steel_frame");

        assertEquals(2, allocation.fromPlayer());
        assertEquals(0, allocation.fromAe());
        assertEquals(0, allocation.toCraft());
    }
}
