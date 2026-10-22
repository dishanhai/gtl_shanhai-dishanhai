package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Row;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Tab;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void slotRowsKeepTheTrailingPlusAndWrapItToTheNextLine() {
        assertEquals(3, ShopEntryEditLayout.perLine(20));
        assertEquals(4, ShopEntryEditLayout.perLine(96));
        assertEquals(1, ShopSlotRows.slotCount(0, 20), "空清单保留加号");
        assertEquals(5, ShopSlotRows.slotCount(4, 20));
        assertEquals(2, ShopEntryEditLayout.lines(5, 4), "满行后的加号独占下一行");
        assertEquals(20, ShopSlotRows.slotCount(20, 20), "满上限不再加号");
        assertEquals(21, ShopSlotRows.slotCount(21, 20), "旧数据超上限仍完整显示");
        assertEquals(0, ShopEntryEditLayout.lines(0, 4));
    }

    @Test
    void slotCoordinatesAndHeightsUseTheRowsActualPitch() {
        assertEquals(12, ShopEntryEditLayout.slotX(12, 4, 4));
        assertEquals(84, ShopEntryEditLayout.slotX(12, 3, 4));
        assertEquals(50, ShopEntryEditLayout.slotY(20, 4, 4, 30));
        assertEquals(44, ShopEntryEditLayout.slotY(20, 4, 4, 24));
        assertEquals(60, ShopSlotRows.height(5, 4, 30, 30));
        assertEquals(44, ShopSlotRows.height(5, 4, 24, 20));
        assertEquals(20, ShopSlotRows.height(4, 4, 24, 20));
        assertEquals(0, ShopSlotRows.height(0, 4, 30, 30));
        assertEquals(-72, ShopEntryEditLayout.slotX(12, 4, 4) - ShopEntryEditLayout.slotX(12, 3, 4),
                "跨行补位按旧新坐标差移动");
    }

    @Test
    void slotHitTestingRejectsGapsAndUnusedCellsOnEveryLine() {
        assertEquals(0, slotAt(12, 20));
        assertEquals(3, slotAt(103.9, 39.9));
        assertEquals(4, slotAt(12, 50));
        assertEquals(-1, slotAt(11.9, 20));
        assertEquals(-1, slotAt(12, 19.9));
        assertEquals(-1, slotAt(32, 20), "右边界与横向间隙不算槽位");
        assertEquals(-1, slotAt(12, 40), "数量行不算图标");
        assertEquals(-1, slotAt(12, 49.9));
        assertEquals(-1, slotAt(36, 50), "末行空位拒收");
        assertEquals(-1, slotAt(108, 20));
        assertEquals(-1, slotAt(12, 80));
        assertEquals(-1, slotAt(Double.NaN, 20));
        assertEquals(-1, slotAt(12, Double.POSITIVE_INFINITY));
        assertEquals(4, ShopEntryEditLayout.slotIndexAt(12, 71, 12, 41, 5, 4, 30, 9),
                "第二行数量文字可用同一几何，保留精确 long 编辑入口");
        assertEquals(-1, ShopEntryEditLayout.slotIndexAt(12, 70, 12, 41, 5, 4, 30, 9));
    }

    private static int slotAt(double mx, double my) {
        return ShopEntryEditLayout.slotIndexAt(mx, my, 12, 20, 5, 4, 30, 20);
    }

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

    @Test
    void replaceAtSplicesThePickedListInPlaceOfOneSlot() {
        List<String> l = new ArrayList<>(List.of("a", "b", "c"));
        assertEquals(1, ShopEntryEditLayout.replaceAt(l, 1, List.of("x", "y")), "返回新内容插在哪");
        assertEquals(List.of("a", "x", "y", "c"), l, "一格换成多项，顺序照选择器清单");

        assertEquals(0, ShopEntryEditLayout.replaceAt(l, 0, List.of()));
        assertEquals(List.of("x", "y", "c"), l, "清单清空再确认 = 删掉这一格");

        assertEquals(3, ShopEntryEditLayout.replaceAt(l, 7, List.of("z")));
        assertEquals(List.of("x", "y", "c", "z"), l, "越界（如并行数量表比格子短）就接在末尾，不抛异常");
        assertEquals(4, ShopEntryEditLayout.replaceAt(l, -1, List.of("w")));
        assertEquals(List.of("x", "y", "c", "z", "w"), l);
    }
}
