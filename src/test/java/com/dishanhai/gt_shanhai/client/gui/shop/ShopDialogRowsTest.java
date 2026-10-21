package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopDialogRowsTest {

    @Test
    void fitFromCountsRowsThatFitAndAlwaysShowsAtLeastOne() {
        int[] h = {20, 20, 29, 20};
        assertEquals(2, ShopDialogRows.fitFrom(h, 0, 60), "20+20 放得下，再加 29 超出");
        assertEquals(3, ShopDialogRows.fitFrom(h, 0, 69));
        assertEquals(2, ShopDialogRows.fitFrom(h, 2, 100), "到尾为止");
        assertEquals(1, ShopDialogRows.fitFrom(new int[]{80}, 0, 60), "单行比列表还高也照样显示，不卡住");
        assertEquals(0, ShopDialogRows.fitFrom(h, 4, 60));
    }

    @Test
    void maxFirstFillsTheLastScreen() {
        int[] h = {20, 20, 29, 20};
        assertEquals(2, ShopDialogRows.maxFirst(h, 60), "29+20 放得下，再加 20 超出");
        assertEquals(0, ShopDialogRows.maxFirst(h, 200), "全放得下就不滚");
        assertEquals(1, ShopDialogRows.maxFirst(new int[]{20, 80}, 60), "最后一行太高时停在最后一行");
        assertEquals(0, ShopDialogRows.maxFirst(new int[0], 60));
    }

    @Test
    void uniformRowsBehaveLikePlainPaging() {
        int[] h = new int[156];
        java.util.Arrays.fill(h, 11);
        assertEquals(20, ShopDialogRows.fitFrom(h, 0, 224));
        assertEquals(136, ShopDialogRows.maxFirst(h, 224));
    }

    @Test
    void scrollToShowBringsTheRowFullyIntoView() {
        int[] h = {20, 20, 20, 20, 20, 20};
        assertEquals(1, ShopDialogRows.scrollToShow(h, 3, 1, 60), "在上面：顶到第一行");
        assertEquals(3, ShopDialogRows.scrollToShow(h, 3, 4, 60), "已经露出：不动");
        assertEquals(3, ShopDialogRows.scrollToShow(h, 0, 5, 60), "在下面：成为最后一行");
        int[] tall = {20, 20, 80};
        assertEquals(2, ShopDialogRows.scrollToShow(tall, 0, 2, 60), "太高的行顶到第一行");
    }

    @Test
    void thumbIsProportionalAndTracksTheScroll() {
        assertArrayEquals(new int[]{0, 100}, ShopDialogRows.thumb(10, 10, 0, 0, 100), "没溢出：把手占满");
        int[] top = ShopDialogRows.thumb(100, 20, 0, 80, 100);
        int[] bottom = ShopDialogRows.thumb(100, 20, 80, 80, 100);
        assertEquals(0, top[0]);
        assertEquals(20, top[1]);
        assertEquals(80, bottom[0], "滚到底时把手贴底");
        assertEquals(8, ShopDialogRows.thumb(10_000, 20, 0, 9_980, 100)[1], "把手最少 8px，拖得住");
    }

    @Test
    void firstAtMapsTrackPositionBackToARow() {
        assertEquals(0, ShopDialogRows.firstAt(-30, 20, 100, 80), "轨道上方夹到 0");
        assertEquals(80, ShopDialogRows.firstAt(500, 20, 100, 80), "轨道下方夹到底");
        assertEquals(40, ShopDialogRows.firstAt(50, 20, 100, 80), "按住把手中心：轨道中点对应一半");
        assertEquals(0, ShopDialogRows.firstAt(50, 100, 100, 0));
        assertTrue(ShopDialogRows.firstAt(60, 20, 100, 80) > ShopDialogRows.firstAt(40, 20, 100, 80));
    }
}
