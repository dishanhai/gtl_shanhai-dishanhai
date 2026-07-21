package com.dishanhai.gt_shanhai.common.ae2.quantum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QuantumCraftingStatusTest {

    @Test
    void missingInputWithUpstreamWorkIsReportedAsWaitingUpstream() {
        assertEquals(QuantumCraftingStatus.State.WAITING_UPSTREAM,
                QuantumCraftingCPULogic.classifyCraftingStatus(0L, true, true, 0L, 2_293_200L));
        assertEquals(QuantumCraftingStatus.State.WAITING_UPSTREAM,
                QuantumCraftingCPULogic.classifyCraftingStatus(0L, true, true, 32L, 0L));
    }

    @Test
    void missingInputWithoutUpstreamWorkIsReportedAsRealShortage() {
        assertEquals(QuantumCraftingStatus.State.MISSING_INPUT,
                QuantumCraftingCPULogic.classifyCraftingStatus(0L, true, true, 0L, 0L));
    }

    @Test
    void readyInputsExposeProviderAndDispatchStates() {
        assertEquals(QuantumCraftingStatus.State.NO_PROVIDER,
                QuantumCraftingCPULogic.classifyCraftingStatus(1L, false, false, 0L, 0L));
        assertEquals(QuantumCraftingStatus.State.PROVIDER_BUSY,
                QuantumCraftingCPULogic.classifyCraftingStatus(1L, true, false, 0L, 0L));
        assertEquals(QuantumCraftingStatus.State.READY_TO_DISPATCH,
                QuantumCraftingCPULogic.classifyCraftingStatus(1L, true, true, 0L, 0L));
    }
}
