package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：使用可控制時鐘驗證分析數字，不靠 sleep 或遊戲啟動。 */
class ShanhaiLoadingMetricsTest {
    private final AtomicLong clock = new AtomicLong();

    private ShanhaiLoadingMetrics metrics() { return new ShanhaiLoadingMetrics("world", clock::get, 100); }

    @Test void recordsWallCpuAllocationsAndFailuresWithoutInventingUnsupportedMetrics() {
        var metrics = metrics();
        metrics.record("recipes", "execute", 1000, 600, 4096, 150, false);
        metrics.record("recipes", "execute", 500, -1, -1, 130, true);
        clock.set(5000);
        var report = metrics.snapshot("ready", 120);
        var entry = report.entries().get(0);
        assertEquals(1500, entry.elapsedNanos());
        assertEquals(600, entry.cpuNanos());
        assertEquals(1, entry.cpuSamples());
        assertEquals(4096, entry.allocatedBytes());
        assertEquals(1, entry.allocationSamples());
        assertEquals(2, entry.calls());
        assertEquals(1, entry.failures());
        assertEquals(50, report.peakObservedHeapBytes() - report.initialHeapBytes());
        assertEquals(20, report.heapDeltaBytes());
    }

    @Test void userTimeInWorldCreationSettingsIsExcludedFromTheLoadDuration() {
        var metrics = metrics();
        clock.set(1000); metrics.pause();
        clock.set(11_000); metrics.resume();
        clock.set(12_000);
        assertEquals(2000, metrics.snapshot("ready", 100).elapsedNanos());
    }

    @Test void slowRecipeRetentionIsBoundedAndDoesNotMixWithAggregatedNamespaces() {
        var metrics = metrics();
        for (int i = 0; i < 200; i++) {
            metrics.record("recipe/gt_shanhai", "recipe namespace", i, -1, -1, 100, false);
            metrics.slowRecipe("gt_shanhai:recipe_" + i, i, -1, -1, 100, i == 199);
        }
        var report = metrics.snapshot("ready", 100);
        assertEquals(1, report.entries().size());
        assertEquals(200, report.entries().get(0).calls());
        assertEquals(32, report.slowRecipes().size());
        assertEquals("gt_shanhai:recipe_199", report.slowRecipes().get(0).name());
        assertEquals(199, report.slowRecipes().get(0).elapsedNanos());
        assertEquals(1, report.slowRecipes().get(0).failures());
    }

    @Test void parallelSamplesAreAggregatedButNeverUsedAsPhaseElapsedTime() throws Exception {
        var metrics = metrics();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 1000; i++) pool.submit(() -> metrics.record("ModelManager", "execute", 100, 10, 20, 110, false));
        }
        clock.set(1000);
        var report = metrics.snapshot("ready", 100);
        assertEquals(1000, report.elapsedNanos());
        assertEquals(100_000, report.entries().get(0).elapsedNanos());
        assertEquals(1000, report.entries().get(0).calls());
    }

    @Test void snapshotsRemainImmutableAndCompletedRunsRejectLateWrites() {
        var metrics = metrics();
        metrics.record("first", "execute", 100, 10, 20, 110, false);
        var report = metrics.finish("cancelled", 100);
        metrics.record("late", "execute", 999, 10, 20, 100, false);
        assertEquals(1, report.entries().size());
        assertEquals(1, metrics.snapshot("cancelled", 100).entries().size());
        assertThrows(UnsupportedOperationException.class, () -> report.entries().clear());
    }
}
