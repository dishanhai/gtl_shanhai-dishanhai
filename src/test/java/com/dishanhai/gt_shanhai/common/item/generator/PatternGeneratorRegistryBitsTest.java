package com.dishanhai.gt_shanhai.common.item.generator;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternGeneratorRegistryBitsTest {
    @Test
    void sparseRemappedIdsNeedMoreBitsThanTheEntryCount() {
        var registryIds = List.of(0, 18, 129);
        var hits = new BitSet();
        hits.set(129);

        assertTrue(hits.length() > registryIds.size());
        assertEquals(hits.length(), PatternGeneratorRegistryBits.exclusiveIdLimit(registryIds, Integer::intValue));
    }

    @Test
    void denseRegistriesPreserveTheOldLimitAndEmptyRegistriesNeedNoBits() {
        assertEquals(3, PatternGeneratorRegistryBits.exclusiveIdLimit(List.of(2, 0, 1), Integer::intValue));
        assertEquals(0, PatternGeneratorRegistryBits.exclusiveIdLimit(List.<Integer>of(), Integer::intValue));
    }

    @Test
    void boundsFollowCurrentIdsAfterRegistryRemapping() {
        var registry = java.util.Map.of("item", 1);
        assertEquals(2, PatternGeneratorRegistryBits.exclusiveIdLimit(List.of("item"), registry::get));
        var remapped = java.util.Map.of("item", 96);
        assertEquals(97, PatternGeneratorRegistryBits.exclusiveIdLimit(List.of("item"), remapped::get));
    }
}
