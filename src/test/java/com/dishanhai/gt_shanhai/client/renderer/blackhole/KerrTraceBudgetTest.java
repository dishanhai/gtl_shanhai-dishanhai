package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KerrTraceBudgetTest {
    @Test
    void highResolutionHistoryFitsTheTextureBudgetAfterAlignmentAndHysteresis() {
        for (var resolution : new int[][] {{3840, 2160}, {5120, 1440}, {7680, 4320}, {129, 16384}}) {
            for (int tile : new int[] {8, 16}) {
                var extent = KerrTraceBudget.extent(1, resolution[0], resolution[1], 1920, 1080, tile);
                assertTrue((long) extent.width() * extent.height() <= KerrTraceBudget.TRACE_TARGET_PIXELS);
                assertEquals(0, extent.width() % tile);
                assertEquals(0, extent.height() % tile);
            }
        }
        var hd = KerrTraceBudget.extent(1, 1920, 1080, 0, 0, 8);
        assertEquals(new KerrTraceBudget.TraceExtent(1920, 1080), hd);
    }

}
