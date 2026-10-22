package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 三栏、像素行、收合和坐标换算共用几何（山海署名）。 */
class MultiPickerLayoutTest {
    @Test void columnsFitSixHundredAndDoNotOverlap() {
        var c = MultiPickerLayout.columns(0, 0, 600, 380);
        assertEquals(104, c.sidebar.getW()); assertEquals(234, c.selected.getW());
        assertTrue(c.sidebar.getX() + c.sidebar.getW() < c.grid.getX());
        assertTrue(c.grid.getX() + c.grid.getW() < c.selected.getX());
        assertTrue(c.selected.getX() + c.selected.getW() <= 588);
    }
    @Test void groupedRowsCollapseAndStickyHeaderPushesOut() {
        var sections = List.of(new PickerSection("minecraft", "Minecraft", new int[]{0, 1, 2}),
                new PickerSection("a", "A", new int[]{3}));
        var g = MultiPickerLayout.grid(new int[]{0, 1, 2, 3}, sections, true, Set.of(), 2, 22, 16);
        assertEquals(98, g.height);
        assertEquals(-1, MultiPickerLayout.rowAtY(g.rows, -1));
        assertEquals(0, MultiPickerLayout.rowAtY(g.rows, 0));
        assertEquals(1, MultiPickerLayout.rowAtY(g.rows, 16));
        assertEquals(-1, MultiPickerLayout.rowAtY(g.rows, 98));
        assertEquals(0, MultiPickerLayout.stickyHeader(g.rows, 20));
        assertEquals(-6, MultiPickerLayout.stickyOffset(g.rows, 50, 16));
        var collapsed = MultiPickerLayout.grid(new int[]{0, 1, 2, 3}, sections, true, Set.of("minecraft"), 2, 22, 16);
        assertEquals(54, collapsed.height);
    }
    @Test void variableRowsUseExclusiveBottomAndInsertionGaps() {
        int[] offsets = MultiPickerLayout.offsets(new int[]{30, 70, 20});
        assertArrayEquals(new int[]{0, 30, 100, 120}, offsets);
        assertEquals(1, MultiPickerLayout.indexAt(offsets, 30));
        assertEquals(-1, MultiPickerLayout.indexAt(offsets, 120));
        assertEquals(0, MultiPickerLayout.insertionGap(offsets, 14));
        assertEquals(1, MultiPickerLayout.insertionGap(offsets, 16));
        assertEquals(3, MultiPickerLayout.insertionGap(offsets, 200));
        var px = MultiPickerLayout.toPixels(10, 20, 30, 40, 0.5f, 2, 3, 1600, 900, 800, 450);
        assertEquals(14, px.getX()); assertEquals(26, px.getY());
        assertEquals(30, px.getW()); assertEquals(40, px.getH());
    }
}
