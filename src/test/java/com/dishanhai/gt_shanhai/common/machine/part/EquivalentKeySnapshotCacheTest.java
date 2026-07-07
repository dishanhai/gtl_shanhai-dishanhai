package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

public class EquivalentKeySnapshotCacheTest {

    @Test
    void loadsSnapshotOnlyOnceAcrossRepeatedLookups() {
        AtomicInteger loadCalls = new AtomicInteger();
        EquivalentKeySnapshotCache<String> cache = new EquivalentKeySnapshotCache<>(EquivalentKeySnapshotCacheTest::normalize);

        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        assertIterableEquals(Arrays.asList("water#empty"), cache.getEquivalentRawKeys("water", () -> load(loadCalls)));
        assertEquals(1, loadCalls.get());
    }

    @Test
    void extractionAdjustsCachedEquivalentAmountWithoutReload() {
        AtomicInteger loadCalls = new AtomicInteger();
        EquivalentKeySnapshotCache<String> cache = new EquivalentKeySnapshotCache<>(EquivalentKeySnapshotCacheTest::normalize);

        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        cache.recordChange("water#empty", -400L);

        assertEquals(600L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        assertIterableEquals(Arrays.asList("water#empty"), cache.getEquivalentRawKeys("water", () -> load(loadCalls)));
        assertEquals(1, loadCalls.get());
    }

    @Test
    void invalidateForcesSnapshotReload() {
        AtomicInteger loadCalls = new AtomicInteger();
        EquivalentKeySnapshotCache<String> cache = new EquivalentKeySnapshotCache<>(EquivalentKeySnapshotCacheTest::normalize);

        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        cache.invalidate();
        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        assertEquals(2, loadCalls.get());
    }

    private static String normalize(String key) {
        if (key.endsWith("#empty")) {
            return key.substring(0, key.length() - 6);
        }
        return key;
    }

    private static List<EquivalentKeySnapshotCache.Entry<String>> load(AtomicInteger loadCalls) {
        loadCalls.incrementAndGet();
        return Arrays.asList(new EquivalentKeySnapshotCache.Entry<>("water#empty", 1000L));
    }
}
