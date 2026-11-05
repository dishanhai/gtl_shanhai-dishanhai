package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：小卡片与大数价格都不得挤占名称、分类或相邻卡片。 */
class ShopCardLayoutTest {
    private Object layout(int w, int h, int amount, int extra, boolean icon) throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.gui.shop.ShopCardLayout")
                .getDeclaredMethod("of", int.class, int.class, int.class, int.class, boolean.class)
                .invoke(null, w, h, amount, extra, icon);
    }
    private int value(Object layout, String method) throws Exception {
        return (int) layout.getClass().getDeclaredMethod(method).invoke(layout);
    }
    private boolean flag(Object layout, String method) throws Exception {
        return (boolean) layout.getClass().getDeclaredMethod(method).invoke(layout);
    }

    @Test
    void everySupportedSizeKeepsTheIconAboveThePriceAndAllPricePartsInside() throws Exception {
        for (int w : new int[]{48, 60, 96, 164}) for (int h : new int[]{28, 32, 38, 48, 72})
            for (int amount : new int[]{6, 30, 120}) for (int extra : new int[]{0, 12, 60}) {
                var l = layout(w, h, amount, extra, true);
                assertTrue(3 + value(l, "slotSize") + 2 <= value(l, "priceY"));
                assertTrue(value(l, "numberWidth") >= 12);
                assertTrue(2 + value(l, "numberWidth") <= w - 2);
                if (flag(l, "showCost")) {
                    assertTrue(value(l, "costX") >= 2 + value(l, "numberWidth") + 2);
                    assertTrue(value(l, "costX") + 10 <= w - 2);
                }
                if (flag(l, "showExtra")) {
                    assertTrue(value(l, "extraX") >= value(l, "costX") + 12);
                    assertTrue(value(l, "extraX") + extra <= w - 2);
                }
                if (flag(l, "showCategory")) assertTrue(14 + 9 + 2 <= value(l, "priceY"));
                assertTrue(value(l, "textX") + value(l, "textWidth") <= w - 3);
            }
    }

    @Test
    void compactCardsOmitTheSecondTextLineAndLargeCardsKeepIt() throws Exception {
        assertFalse(flag(layout(60, 28, 20, 12, true), "showCategory"));
        assertFalse(flag(layout(60, 32, 20, 12, true), "showCategory"));
        assertTrue(flag(layout(60, 38, 20, 12, true), "showCategory"));
        var free = layout(48, 28, 12, 0, false);
        assertFalse(flag(free, "showCost"));
        assertFalse(flag(free, "showExtra"));
        assertEquals(20, value(layout(96, 48, 20, 12, true), "slotSize"));
    }
}
