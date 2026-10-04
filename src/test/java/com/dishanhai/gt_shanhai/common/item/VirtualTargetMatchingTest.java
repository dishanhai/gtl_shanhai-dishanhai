package com.dishanhai.gt_shanhai.common.item;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// Author: dishanhai. Quantity tests use real maps without a Minecraft registry.
class VirtualTargetMatchingTest {

    private final Object2LongOpenHashMap<String> targets = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<String> inventory = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<String> catalyst = new Object2LongOpenHashMap<>();

    @Test
    void matchesTheActualVariantInsteadOfAnIngredientSample() {
        targets.put("mold:tagged", 1L);
        inventory.put("mold:tagged", 1L);
        assertEquals("mold:tagged", VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1L, key -> key.startsWith("mold:")));
    }

    @Test
    void doesNotScanUnregisteredRealStock() {
        inventory.put("mold:real", 100L);
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1L, key -> true));
    }

    @Test
    void doesNotSumTheMainInventoryAndItsCatalystMirror() {
        targets.put("water", 1000L);
        inventory.put("water", 600L);
        catalyst.put("water", 600L);
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1000L, key -> true));
    }

    @Test
    void acceptsPresenceInThePersistedCatalystMirror() {
        targets.put("water:tagged", 1000L);
        catalyst.put("water:tagged", 1000L);
        assertEquals("water:tagged", VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1000L, key -> key.endsWith(":tagged")));
    }

    @Test
    void capsPresenceAtTheRegisteredVirtualAmount() {
        targets.put("water", 10L);
        inventory.put("water", 1000L);
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1000L, key -> true));
    }

    @Test
    void rejectsAStaleIdentityWithoutPresence() {
        targets.put("water", 1000L);
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1L, key -> true));
    }

    @Test
    void checksThePredicateEvenWhenTheQuantityIsSufficient() {
        targets.put("water:wrong-tag", 1000L);
        inventory.put("water:wrong-tag", 1000L);
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 1000L, key -> key.equals("water:required-tag")));
    }

    @Test
    void keepsLongAmountsWithoutNarrowingOrOverflow() {
        targets.put("water", Long.MAX_VALUE);
        inventory.put("water", Long.MAX_VALUE);
        catalyst.put("water", Long.MAX_VALUE);
        assertEquals("water", VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, Long.MAX_VALUE, key -> true));
        inventory.put("water", (long) Integer.MAX_VALUE + 1000L);
        catalyst.clear();
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, (long) Integer.MAX_VALUE + 1001L, key -> true));
    }

    @Test
    void rejectsNonPositiveRequests() {
        targets.put("water", 1000L);
        inventory.put("water", 1000L);
        assertNull(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalyst, 0L, key -> true));
    }

    @Test
    void removesOnlyTheVirtualOverlapFromTheCatalystView() {
        targets.put("water", 1000L);
        inventory.put("water", 600L);
        catalyst.put("water", 600L);
        var distinct = VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(targets, inventory, catalyst);
        assertEquals(0L, distinct.getLong("water"));
        assertEquals(600L, catalyst.getLong("water"), "Simulation must not mutate the persisted mirror.");
    }

    @Test
    void leavesAdditionalRealCatalystStockInTheView() {
        targets.put("mold", 5L);
        inventory.put("mold", 10L);
        catalyst.put("mold", 9L);
        assertEquals(4L, VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(
                targets, inventory, catalyst).getLong("mold"));
    }

    @Test
    void doesNotRemovePresenceThatExistsOnlyInTheMirror() {
        targets.put("water", 1000L);
        catalyst.put("water", 1000L);
        assertEquals(1000L, VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(
                targets, inventory, catalyst).getLong("water"));
    }

    @Test
    void removesLongMirrorsWithoutOverflow() {
        targets.put("water", Long.MAX_VALUE);
        inventory.put("water", Long.MAX_VALUE);
        catalyst.put("water", Long.MAX_VALUE);
        assertEquals(0L, VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(
                targets, inventory, catalyst).getLong("water"));
    }

    @Test
    void keepsTheOriginalCatalystViewWhenThereAreNoVirtualTargets() {
        catalyst.put("mold:real", 1000L);
        assertSame(catalyst, VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(
                targets, inventory, catalyst));
    }
}
