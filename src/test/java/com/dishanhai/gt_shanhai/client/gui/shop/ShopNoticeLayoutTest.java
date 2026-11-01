package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：長提示必須逐頁完整顯示，三條並排也不能永久裁掉文字。 */
class ShopNoticeLayoutTest {
    @Test void ordinaryNoticesKeepTheirFullHeightAndFiveSecondLifetime() {
        var page = ShopNoticeLayout.page(2, 102, 9, 0L);
        assertEquals(0, page.from());
        assertEquals(2, page.to());
        assertEquals(1, page.count());
        assertEquals(30, page.height());
        assertEquals(5000L, page.durationMs());
    }

    @Test void longNoticesShowEveryLineWithStableHeightAndEnoughReadingTime() {
        var first = ShopNoticeLayout.page(16, 102, 9, 0L);
        var second = ShopNoticeLayout.page(16, 102, 9, 3000L);
        assertEquals(2, first.count());
        assertEquals(9, first.to());
        assertEquals(9, second.from());
        assertEquals(16, second.to());
        assertEquals(first.height(), second.height());
        assertTrue(first.height() <= 102);
        assertEquals(6000L, first.durationMs());
        assertEquals(1, ShopNoticeLayout.page(16, 102, 9, 9000L).index());
    }

    @Test void threeLongNoticesFitThePanelAndPaginationDoesNotLoseLines() {
        int budget = ShopNoticeLayout.rowBudget(414, 3);
        var first = ShopNoticeLayout.page(1000, budget, 9, 0L);
        assertTrue((first.height() + 4) * 3 <= 414 - 96);
        var seen = new HashSet<Integer>();
        for (int i = 0; i < first.count(); i++) {
            var page = ShopNoticeLayout.page(1000, budget, 9, i * 3000L);
            assertTrue(page.height() <= budget);
            assertTrue(page.to() > page.from());
            for (int line = page.from(); line < page.to(); line++) assertTrue(seen.add(line));
        }
        assertEquals(1000, seen.size());
    }

    @Test void emptyAndSmallNoticesStillHaveAValidVisiblePage() {
        var empty = ShopNoticeLayout.page(0, 40, 9, -10L);
        assertEquals(0, empty.from());
        assertEquals(0, empty.to());
        assertEquals(1, empty.count());
        assertTrue(empty.height() >= 22);
        var small = ShopNoticeLayout.page(12, 40, 9, 0L);
        assertTrue(small.height() <= 40);
        assertTrue(small.count() > 1);
    }

    @Test void balancePopupsReserveTheirOwnAreaAboveLongNotices() {
        int reservedTop = 170;
        int budget = ShopNoticeLayout.rowBudget(414, 3, reservedTop);
        var page = ShopNoticeLayout.page(100, budget, 9, 0L);
        int stackTop = 414 - 10 - 3 * (page.height() + 4);
        assertTrue(stackTop >= reservedTop - 10);
        assertTrue(page.height() <= budget);
    }
}
