package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex.Suggestion;
import java.util.List;

/** 山海：五笔一页，保留联想排序及页内选择，更新查询或来源时回到首页。 */
final class ShopSearchSuggestionPages {
    private static final int PAGE_SIZE = 5;
    private List<Suggestion> values = List.of();
    private int page, selected;

    void update(List<Suggestion> values) {
        this.values = List.copyOf(values);
        page = selected = 0;
    }

    int pageIndex() { return page; }
    int pageCount() { return (values.size() + PAGE_SIZE - 1) / PAGE_SIZE; }
    int selectedIndex() { return selected; }

    List<Suggestion> current() {
        int start = page * PAGE_SIZE;
        return values.subList(start, Math.min(values.size(), start + PAGE_SIZE));
    }

    boolean movePage(int delta) {
        int next = Math.max(0, Math.min(Math.max(0, pageCount() - 1), page + delta));
        if (next == page) return false;
        page = next;
        selected = 0;
        return true;
    }

    void select(int index) { selected = Math.max(0, Math.min(Math.max(0, current().size() - 1), index)); }

    void moveSelection(int delta) {
        if (!current().isEmpty()) selected = Math.floorMod(selected + delta, current().size());
    }
}
