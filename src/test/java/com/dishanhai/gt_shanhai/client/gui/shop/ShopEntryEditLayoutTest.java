package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Row;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Tab;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopEntryEditLayoutTest {

    @Test
    void fiveTabsInFixedOrder() {
        Tab[] tabs = Tab.values();
        assertEquals(5, tabs.length);
        assertEquals("商品", tabs[0].label);
        assertEquals("价格", tabs[1].label);
        assertEquals("限购", tabs[2].label);
        assertEquals("奖励", tabs[3].label);
        assertEquals("跳转", tabs[4].label);
    }

    @Test
    void everyRowBelongsToExactlyOneTabAndNoTabIsEmpty() {
        Set<Row> seen = EnumSet.noneOf(Row.class);
        for (Tab t : Tab.values()) {
            List<Row> rows = ShopEntryEditLayout.rowsOf(t);
            assertFalse(rows.isEmpty(), t + " 至少有一行");
            for (Row r : rows) {
                assertEquals(t, r.tab);
                assertTrue(seen.add(r), r + " 只能出现在一个页签");
            }
        }
        assertEquals(EnumSet.allOf(Row.class), seen, "每一行都有归属");
    }

    @Test
    void rowsKeepDeclarationOrderWithinATab() {
        assertEquals(List.of(Row.FREE, Row.SPARK, Row.COIN, Row.ITEM, Row.FLUID, Row.PRICE_HINT, Row.DISCOUNT),
                ShopEntryEditLayout.rowsOf(Tab.PRICE));
        assertEquals(Row.GOODS, ShopEntryEditLayout.rowsOf(Tab.GOODS).get(0), "商品页先选商品");
    }

    @Test
    void stackSkipsHiddenRowsWithoutLeavingAGap() {
        assertArrayEquals(new int[]{0, 22, 22}, ShopEntryEditLayout.stack(new int[]{14, 0, 30}, 8),
                "高 0 的行不占位，也不多留间距");
        assertArrayEquals(new int[]{0, 0, 0}, ShopEntryEditLayout.stack(new int[]{0, 0, 14}, 8),
                "开头的隐藏行不留间距");
        assertArrayEquals(new int[0], ShopEntryEditLayout.stack(new int[0], 8));
    }

    @Test
    void contentHeightHasNoTrailingGap() {
        assertEquals(52, ShopEntryEditLayout.contentHeight(new int[]{14, 0, 30}, 8));
        assertEquals(14, ShopEntryEditLayout.contentHeight(new int[]{14, 0}, 8), "结尾的隐藏行不加间距");
        assertEquals(0, ShopEntryEditLayout.contentHeight(new int[]{0, 0}, 8));
    }

    @Test
    void scrollIsClampedToContent() {
        assertEquals(12, ShopEntryEditLayout.maxScroll(52, 40));
        assertEquals(0, ShopEntryEditLayout.maxScroll(30, 40), "放得下就不滚");
        assertEquals(0, ShopEntryEditLayout.clampScroll(-5, 52, 40));
        assertEquals(12, ShopEntryEditLayout.clampScroll(100, 52, 40));
        assertEquals(7, ShopEntryEditLayout.clampScroll(7, 52, 40));
    }

    @Test
    void scrollToShowRevealsARowWithMinimalMovement() {
        assertEquals(40, ShopEntryEditLayout.scrollToShow(0, 60, 20, 40), "在下面：让它贴着可视区底边");
        assertEquals(10, ShopEntryEditLayout.scrollToShow(30, 10, 10, 40), "在上面：让它贴着顶边");
        assertEquals(5, ShopEntryEditLayout.scrollToShow(5, 10, 20, 40), "已经露出就不动");
        assertEquals(60, ShopEntryEditLayout.scrollToShow(0, 60, 80, 40), "比可视区还高：先露出它的顶端");
    }

    @Test
    void hotkeysOneToFivePickTabs() {
        assertEquals(Tab.GOODS, ShopEntryEditLayout.tabForHotkey(1));
        assertEquals(Tab.LINK, ShopEntryEditLayout.tabForHotkey(5));
        assertNull(ShopEntryEditLayout.tabForHotkey(0));
        assertNull(ShopEntryEditLayout.tabForHotkey(6));
    }

    @Test
    void cycleWrapsAround() {
        assertEquals(Tab.PRICE, ShopEntryEditLayout.cycle(Tab.GOODS, 1));
        assertEquals(Tab.LINK, ShopEntryEditLayout.cycle(Tab.GOODS, -1));
        assertEquals(Tab.GOODS, ShopEntryEditLayout.cycle(Tab.LINK, 1));
    }

    @Test
    void tabsSitSideBySideFromTheLeft() {
        assertArrayEquals(new int[]{10, 64, 112}, ShopEntryEditLayout.tabXs(new int[]{50, 44, 60}, 10, 4));
    }
}
