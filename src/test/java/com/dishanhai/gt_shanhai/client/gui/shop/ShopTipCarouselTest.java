package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：底部小提示必須完整輪播，載入與懸停不能吃掉閱讀時間。 */
class ShopTipCarouselTest {
    private final ShopTipCarousel<String> carousel = new ShopTipCarousel<>();

    @Test void matchesAnyTipCategoryLevelButExcludesHiddenEntriesAndSimilarNames() {
        assertTrue(ShopTipCarousel.isTipCategory(false, "提示", ""));
        assertTrue(ShopTipCarousel.isTipCategory(false, "杂货", "小提示", "包装"));
        assertTrue(ShopTipCarousel.isTipCategory(false, "杂货", "其他", "更多", "提示"));
        assertFalse(ShopTipCarousel.isTipCategory(true, "提示"));
        assertFalse(ShopTipCarousel.isTipCategory(false, "提示商品"));
        assertFalse(ShopTipCarousel.isTipCategory(false, "杂货", ""));
    }

    @Test void filtersEmptyDescriptionsAndPreservesFormattingAndOrder() {
        String description = "&a第一行\n§e第二行";
        carousel.setTips(List.of(tip(1, " \n"), tip(2, "&7  §r"), tip(3, description),
                tip(4, "末项")), 100, text -> List.of(text.split("\n", -1)));
        var page = carousel.page();
        assertEquals(3, page.tip().key());
        assertEquals(description, page.tip().text());
        assertEquals(List.of("&a第一行", "§e第二行"), page.lines());
        assertEquals(2, page.tipCount());
    }

    @Test void showsAllTwoLinePagesForEightSecondsBeforeAdvancingTheTip() {
        carousel.setTips(List.of(tip(1, "a\nb\nc\nd\ne"), tip(2, "next")),
                100, text -> List.of(text.split("\n")));
        carousel.tick(100, false);
        carousel.tick(8099, false);
        assertEquals(List.of("a", "b"), carousel.page().lines());
        carousel.tick(8100, false);
        assertEquals(List.of("c", "d"), carousel.page().lines());
        assertEquals(1, carousel.page().pageIndex());
        assertEquals(3, carousel.page().pageCount());
        carousel.tick(16100, false);
        assertEquals(List.of("e"), carousel.page().lines());
        carousel.tick(24100, false);
        assertEquals(2, carousel.page().tip().key());
        carousel.tick(32100, false);
        assertEquals(1, carousel.page().tip().key());
        assertEquals(0, carousel.page().pageIndex());
    }

    @Test void hoveringPausesAndLeavingResumesTheRemainingReadingTime() {
        carousel.setTips(List.of(tip(1, "one"), tip(2, "two")), 100, List::of);
        carousel.tick(0, false);
        carousel.tick(3000, false);
        carousel.tick(4000, true);
        carousel.tick(40000, true);
        carousel.tick(41000, false);
        carousel.tick(45999, false);
        assertEquals(1, carousel.page().tip().key());
        carousel.tick(46000, false);
        assertEquals(2, carousel.page().tip().key());
    }

    @Test void incrementalLoadingPreservesTheCurrentPageAndClock() {
        var first = tip(1, "a\nb\nc");
        carousel.setTips(List.of(first), 100, text -> List.of(text.split("\n")));
        carousel.tick(0, false);
        carousel.tick(8000, false);
        carousel.tick(11000, false);
        carousel.setTips(List.of(first, tip(2, "new")), 100, text -> List.of(text.split("\n")));
        assertEquals(List.of("c"), carousel.page().lines());
        carousel.tick(16000, false);
        assertEquals(2, carousel.page().tip().key());
    }

    @Test void clearRemovesOldDescriptionsAndRestartsReadingTimeForNewCatalog() {
        carousel.setTips(List.of(tip(1, "old")), 100, List::of);
        carousel.tick(0, false);
        carousel.tick(7000, false);
        carousel.clear();
        assertNull(carousel.page());
        carousel.setTips(List.of(tip(1, "replacement"), tip(2, "second")), 100, List::of);
        carousel.tick(100000, false);
        assertEquals("replacement", carousel.page().tip().text());
        carousel.tick(107999, false);
        assertEquals(1, carousel.page().tip().key());
        carousel.tick(108000, false);
        assertEquals(2, carousel.page().tip().key());
    }

    @Test void rewrapsOnlyWhenContentOrWidthChangesAndRestartsTheResizedPageClock() {
        AtomicInteger wraps = new AtomicInteger();
        var tips = List.of(tip(1, "description"), tip(2, "second"));
        java.util.function.Function<String, List<String>> wrap = text -> {
            wraps.incrementAndGet();
            return List.of(text);
        };
        carousel.setTips(tips, 100, wrap);
        carousel.tick(0, false);
        carousel.tick(7000, false);
        carousel.setTips(new ArrayList<>(tips), 100, wrap);
        assertEquals(2, wraps.get());
        carousel.setTips(tips, 80, wrap);
        assertEquals(4, wraps.get());
        carousel.tick(8000, false);
        carousel.tick(15999, false);
        assertEquals(1, carousel.page().tip().key());
        carousel.tick(16000, false);
        assertEquals(2, carousel.page().tip().key());
    }

    @Test void formattingOnlyDescriptionsDoNotProducePagesOrRepeatWrapping() {
        AtomicInteger wraps = new AtomicInteger();
        var blank = List.of(tip(1, "§7 &r \n"));
        java.util.function.Function<String, List<String>> wrap = text -> {
            wraps.incrementAndGet();
            return List.of(text);
        };
        carousel.setTips(blank, 100, wrap);
        carousel.setTips(new ArrayList<>(blank), 100, wrap);
        assertNull(carousel.page());
        assertEquals(0, wraps.get());
    }

    @Test void wideningTheViewportReplaysTheTipInsteadOfSkippingUnreadCharacters() {
        var tips = List.of(tip(1, "abcdefghijkl"));
        carousel.setTips(tips, 10, text -> List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"));
        carousel.tick(0, false);
        carousel.tick(8000, false);
        assertEquals(List.of("c", "d"), carousel.page().lines());
        carousel.setTips(tips, 30, text -> List.of("abc", "def", "ghi", "jkl"));
        assertEquals(List.of("abc", "def"), carousel.page().lines());
        carousel.tick(9000, false);
        carousel.tick(17000, false);
        assertEquals(List.of("ghi", "jkl"), carousel.page().lines());
    }

    @Test void slowFramesDoNotSkipUnreadPagesAndClockRollbackDoesNotAdvance() {
        carousel.setTips(List.of(tip(1, "a\nb\nc\nd\ne")), 100,
                text -> List.of(text.split("\n")));
        carousel.tick(1000, false);
        carousel.tick(500, false);
        assertEquals(0, carousel.page().pageIndex());
        carousel.tick(100000, false);
        assertEquals(1, carousel.page().pageIndex());
        carousel.tick(107999, false);
        assertEquals(1, carousel.page().pageIndex());
        carousel.tick(108000, false);
        assertEquals(2, carousel.page().pageIndex());
    }

    @Test void noTipsProducesNoPageAndCopiesCallerOwnedLists() {
        carousel.setTips(List.of(), 100, List::of);
        carousel.tick(0, false);
        assertNull(carousel.page());
        var lines = new ArrayList<>(List.of("line"));
        var tips = new ArrayList<>(List.of(tip(1, "text")));
        carousel.setTips(tips, 100, text -> lines);
        lines.clear();
        tips.clear();
        assertEquals(List.of("line"), carousel.page().lines());
    }

    @Test void responsiveRegionsReservePageNumbersAndNeverOverlap() {
        for (int width : List.of(700, 480, 200, 80)) {
            var layout = ShopTipCarousel.layout(5, width, 30, true);
            assertTrue(layout.operationWidth() > 0);
            assertTrue(layout.tipWidth() > 0);
            assertTrue(layout.operationX() + layout.operationWidth() <= layout.tipX());
            assertTrue(layout.tipX() + layout.tipWidth() < layout.pageX());
            assertTrue(layout.pageX() + 30 <= 5 + width);
        }
        var layout = ShopTipCarousel.layout(5, 700, 30, false);
        assertEquals(0, layout.tipWidth());
        assertTrue(layout.operationWidth() > 600);
    }

    private static ShopTipCarousel.Tip tip(long key, String text) {
        return new ShopTipCarousel.Tip(key, text);
    }
}
