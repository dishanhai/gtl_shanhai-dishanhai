package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EquivalentKeySnapshotCacheTest {

    private static final Path HATCH_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "MEDiskHatchPartMachine.java");

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
    void invalidateForcesSnapshotReload() {
        AtomicInteger loadCalls = new AtomicInteger();
        EquivalentKeySnapshotCache<String> cache = new EquivalentKeySnapshotCache<>(EquivalentKeySnapshotCacheTest::normalize);

        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        cache.invalidate();
        assertEquals(1000L, cache.getNormalizedAmount("water", () -> load(loadCalls)));
        assertEquals(2, loadCalls.get());
    }

    @Test
    void equivalentAmountsSaturateInsteadOfWrapping() {
        EquivalentKeySnapshotCache<String> cache = new EquivalentKeySnapshotCache<>(EquivalentKeySnapshotCacheTest::normalize);
        cache.replace(List.of(
                new EquivalentKeySnapshotCache.Entry<>("water#empty", Long.MAX_VALUE),
                new EquivalentKeySnapshotCache.Entry<>("water", 2_400_000_000_000L)));

        assertEquals(Long.MAX_VALUE, cache.getNormalizedAmount("water", List::of));
    }

    @Test
    void diskHatchReloadsAfterMutationInsteadOfApplyingDeltas() throws IOException {
        String source = Files.readString(HATCH_SOURCE);

        assertFalse(source.contains("equivalentKeyCache.recordChange"),
                "无限盘返回量不等于库存变化量，不能用 delta 修改等价键快照");
        assertFalse(source.contains("cache.recordChange"),
                "提取后必须让快照失效并从真实存储重读");
        assertTrue(source.contains("equivalentKeyCache.invalidate()"));
        assertTrue(source.contains("cache.invalidate()"));
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
