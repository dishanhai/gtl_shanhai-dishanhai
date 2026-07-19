package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrimordialModuleParallelCapacitySourceTest {

    @Test
    void hugeFluidParallelFallsBackWhenDoubleScalingRoundsAboveInventory() {
        long requestedParallel = 127_140_316_726_905L;
        long availableFluid = 635_701_583_634_525_000L;
        long roundedDemand = (long) (5_000D * requestedParallel);

        assertEquals(56L, roundedDemand - availableFluid, "先固定实机截图对应的 double 舍入差额");
        assertEquals(requestedParallel - 1L,
                PrimordialModuleRecipeLogic.findHighestMatchableParallel(
                        requestedParallel,
                        parallel -> (long) (5_000D * parallel) <= availableFluid));
    }

    @Test
    void tenTimesOutputCapacityReducesOneHundredInputParallelsToTen() {
        long outputCapacity = 100L;
        long amplifiedOutputPerParallel = 10L;

        assertEquals(10L, PrimordialModuleRecipeLogic.findHighestMatchableParallel(
                100L,
                parallel -> parallel * amplifiedOutputPerParallel <= outputCapacity));
    }
}
