package com.dishanhai.gt_shanhai.common.machine.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

class MEStellarStockConfigurationTest {

    @Test
    void clearingAManualSlotStaysCleared() {
        assertNull(MEStellarStockConfiguration.reconcile(null, "ingot", null));
    }

    @Test
    void anUntouchedManualSlotIsKept() {
        assertEquals("ingot", MEStellarStockConfiguration.reconcile("ingot", "ingot", null));
    }

    @Test
    void anUntouchedAutomaticSlotDoesNotBecomeManual() {
        assertNull(MEStellarStockConfiguration.reconcile("dust", null, "dust"));
    }

    @Test
    void clearingAnAutomaticSlotIsAPlayerEdit() {
        assertNull(MEStellarStockConfiguration.reconcile(null, null, "dust"));
    }

    @Test
    void aPlayerAmountSurvivesAnAutomaticRefresh() {
        assertEquals("ingot*64",
                MEStellarStockConfiguration.reconcile("ingot*1000", "ingot*64", "ingot*1000"));
    }

    @Test
    void initialSnapshotDoesNotBakeAutomaticStacks() {
        assertNull(MEStellarStockConfiguration.initialManual("dust", "dust"));
        assertEquals("ingot", MEStellarStockConfiguration.initialManual("ingot", null));
    }

    @Test
    void disablingAPullReleasesOnlyTheBakedCopy() {
        assertTrue(MEStellarStockConfiguration.releaseBaked("dust", "dust", false));
        assertFalse(MEStellarStockConfiguration.releaseBaked("dust", "dust", true));
        assertFalse(MEStellarStockConfiguration.releaseBaked("ingot*64", "ingot*1000", false));
        assertFalse(MEStellarStockConfiguration.releaseBaked("ingot", null, false));
    }

    @Test
    void tagPullSkipsManualAndCancelledKeys() {
        assertFalse(MEStellarStockConfiguration.acceptTagTarget(
                "ingot", Set.of(), Set.of("ingot"), Set.of()));
        assertFalse(MEStellarStockConfiguration.acceptTagTarget(
                "dust", Set.of("dust"), Set.of(), Set.of()));
        assertFalse(MEStellarStockConfiguration.acceptTagTarget(
                "plate", Set.of(), Set.of(), Set.of("plate")));
        assertTrue(MEStellarStockConfiguration.acceptTagTarget(
                "gem", Set.of("dust"), Set.of("ingot"), Set.of("plate")));
    }

    @Test
    void suppressedKeysAreRemovedFromAutomaticTargets() {
        Map<String, Long> targets = new LinkedHashMap<>();
        targets.put("ingot", 64L);
        targets.put("dust", 8L);
        MEStellarStockConfiguration.omitSuppressed(targets, Set.of("ingot"));
        assertEquals(Map.of("dust", 8L), targets);
    }
}
