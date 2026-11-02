package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiLoadingExecutionOrderTest {
    @Test void directWorkPrecedesCumulativeWaitAndProgressObservation() {
        var metrics = new ShanhaiLoadingMetrics("world", () -> 0, 0);
        metrics.record("23 listeners", "listener elapsed (includes waits)", 477000, -1, -1, 0, false);
        metrics.record("RecipeManager", "apply", 18000, 15000, 0, 0, false);
        metrics.record("pack progress", "Forge observed", 61000, -1, -1, 0, false);
        metrics.record("TagManager", "prepare", 300, 200, 0, 0, false);
        var rows = ShanhaiLoadingMetrics.executionFirst(metrics.finish("ready", 0).entries(),
                Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::elapsedNanos));
        assertEquals(List.of("RecipeManager", "TagManager", "23 listeners", "pack progress"),
                rows.stream().map(ShanhaiLoadingMetrics.Entry::name).toList());
    }

    @Test void cheapRecipesDoNotFormatIdsThatCannotEnterSlowestList() {
        var metrics = new ShanhaiLoadingMetrics("world", () -> 0, 0);
        for (int i = 0; i < 32; i++) metrics.slowRecipe("slow:" + i, 100, -1, -1, 0, false);
        Object id = new Object() { @Override public String toString() { fail("unnecessary recipe ID formatting"); return ""; } };
        metrics.slowRecipe(id, 1, -1, -1, 0, false);
        assertEquals(32, metrics.finish("ready", 0).slowRecipes().size());
    }

    @Test void cachedNamespaceCountersKeepExactTotalsAndRejectLateWrites() {
        var metrics = new ShanhaiLoadingMetrics("world", () -> 0, 0);
        for (int i = 0; i < 1000; i++) metrics.recipe("minecraft", "minecraft:" + i, i, i % 10 == 0);
        var report = metrics.finish("ready", 0);
        var row = report.entries().getFirst();
        assertEquals(1000, row.calls()); assertEquals(499500, row.elapsedNanos()); assertEquals(100, row.failures());
        metrics.recipe("minecraft", "late", 1000, false);
        assertEquals(report, metrics.finish("ready", 0));
    }

    @Test void manyNamespacesRemainBoundedAndPreserveOverflowAccounting() {
        var metrics = new ShanhaiLoadingMetrics("world", () -> 0, 0);
        for (int i = 0; i < 2000; i++) metrics.recipe("namespace" + i, "id" + i, 1, false);
        var report = metrics.finish("ready", 0);
        assertTrue(report.entries().size() <= 513);
        assertEquals(2000, report.entries().stream().mapToLong(ShanhaiLoadingMetrics.Entry::calls).sum());
        assertEquals(32, report.slowRecipes().size());
    }
}
