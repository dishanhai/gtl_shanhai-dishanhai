package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex.Suggestion;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：联想分页保留原顺序、完整覆盖剩余项目并隔离页内选择。 */
class ShopSearchSuggestionPagesTest {
    private Object pages() throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.gui.shop.ShopSearchSuggestionPages")
                .getDeclaredConstructor().newInstance();
    }

    private Object call(Object pages, String method) throws Exception {
        return pages.getClass().getDeclaredMethod(method).invoke(pages);
    }

    private Object move(Object pages, String method, int delta) throws Exception {
        return pages.getClass().getDeclaredMethod(method, int.class).invoke(pages, delta);
    }

    private List<Suggestion> values(int count) {
        return IntStream.range(0, count).mapToObj(i -> new Suggestion(i, "Iron " + i, "mod:iron_" + i)).toList();
    }

    private void update(Object pages, List<Suggestion> values) throws Exception {
        pages.getClass().getDeclaredMethod("update", List.class).invoke(pages, values);
    }

    @Test
    void firstFiveStayInOrderAndRemainingPagesCoverEveryOtherMatchOnce() throws Exception {
        var pages = pages();
        var values = values(12);
        update(pages, values);
        assertEquals(3, call(pages, "pageCount"));
        assertEquals(values.subList(0, 5), call(pages, "current"));
        assertEquals(false, move(pages, "movePage", -1));
        assertEquals(true, move(pages, "movePage", 1));
        assertEquals(values.subList(5, 10), call(pages, "current"));
        assertEquals(true, move(pages, "movePage", 1));
        assertEquals(values.subList(10, 12), call(pages, "current"));
        assertEquals(false, move(pages, "movePage", 1));
        assertEquals(2, call(pages, "pageIndex"));
        assertEquals(true, move(pages, "movePage", -1));
        assertEquals(values.subList(5, 10), call(pages, "current"));
    }

    @Test
    void selectionWrapsInsideCurrentPageAndQueryOrSourceReplacementResetsThePage() throws Exception {
        var pages = pages();
        update(pages, values(12));
        move(pages, "moveSelection", -1);
        assertEquals(4, call(pages, "selectedIndex"));
        move(pages, "movePage", 1);
        assertEquals(0, call(pages, "selectedIndex"));
        move(pages, "movePage", 1);
        move(pages, "moveSelection", -1);
        assertEquals(1, call(pages, "selectedIndex"));
        update(pages, values(1));
        assertEquals(0, call(pages, "pageIndex"));
        assertEquals(0, call(pages, "selectedIndex"));
        assertEquals(1, call(pages, "pageCount"));
        update(pages, List.of());
        assertEquals(0, call(pages, "pageCount"));
        assertEquals(List.of(), call(pages, "current"));
        assertEquals(false, move(pages, "movePage", 1));
        move(pages, "moveSelection", 1);
        assertEquals(0, call(pages, "selectedIndex"));
    }

    @Test
    void aFullResourceSourceDoesNotLoseAnyLaterPages() throws Exception {
        var pages = pages();
        update(pages, values(19503));
        assertEquals(3901, call(pages, "pageCount"));
        move(pages, "movePage", 3900);
        assertEquals(values(19503).subList(19500, 19503), call(pages, "current"));
    }
}
