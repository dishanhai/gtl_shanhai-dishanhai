package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 山海：以掃描次數驗證候選 key 快取，不快取網路庫存量。 */
class PatternBufferTagScanCacheTest {

    @Test
    void emptyResultsStillWaitForHundredTicks() {
        PatternBufferStockPull.TagScanCache<String> cache = new PatternBufferStockPull.TagScanCache<>();
        Object grid = new Object();
        Object filter = new Object();
        AtomicInteger scans = new AtomicInteger();
        Supplier<List<String>> scanner = () -> {
            scans.incrementAndGet();
            return List.of();
        };

        cache.matches(grid, filter, 0L, 10L, 32, scanner);
        cache.matches(grid, filter, 0L, 109L, 32, scanner);
        assertEquals(1, scans.get());
        cache.matches(grid, filter, 0L, 110L, 32, scanner);
        assertEquals(2, scans.get());
    }

    @Test
    void gridFilterAndReloadChangesEachForceImmediateRescan() {
        PatternBufferStockPull.TagScanCache<String> cache = new PatternBufferStockPull.TagScanCache<>();
        Object grid = new Object();
        Object filter = new Object();
        AtomicInteger scans = new AtomicInteger();
        Supplier<List<String>> scanner = () -> List.of("key-" + scans.incrementAndGet());

        assertEquals(List.of("key-1"), cache.matches(grid, filter, 0L, 10L, 32, scanner));
        assertEquals(List.of("key-1"), cache.matches(grid, filter, 0L, 11L, 32, scanner));
        grid = new Object();
        assertEquals(List.of("key-2"), cache.matches(grid, filter, 0L, 12L, 32, scanner));
        filter = new Object();
        assertEquals(List.of("key-3"), cache.matches(grid, filter, 0L, 13L, 32, scanner));
        assertEquals(List.of("key-4"), cache.matches(grid, filter, 1L, 14L, 32, scanner));
        assertEquals(4, scans.get());
    }

    @Test
    void capacityTimeRollbackAndSeparateMachinesDoNotReuseOldCandidates() {
        PatternBufferStockPull.TagScanCache<String> first = new PatternBufferStockPull.TagScanCache<>();
        PatternBufferStockPull.TagScanCache<String> second = new PatternBufferStockPull.TagScanCache<>();
        Object grid = new Object();
        Object filter = new Object();
        AtomicInteger scans = new AtomicInteger();
        Supplier<List<String>> scanner = () -> List.of("key-" + scans.incrementAndGet());

        first.matches(grid, filter, 0L, 10L, 32, scanner);
        assertEquals(List.of("key-2"), first.matches(grid, filter, 0L, 11L, 64, scanner));
        assertEquals(List.of("key-3"), first.matches(grid, filter, 0L, 1L, 64, scanner));
        assertEquals(List.of("key-4"), second.matches(grid, filter, 0L, 1L, 64, scanner));
        assertEquals(4, scans.get());
    }
}
