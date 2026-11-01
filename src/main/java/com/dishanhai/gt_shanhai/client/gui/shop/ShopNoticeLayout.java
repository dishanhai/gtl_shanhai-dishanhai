package com.dishanhai.gt_shanhai.client.gui.shop;

/** 山海長提示排版：固定高度自動分頁，每一頁至少保留三秒閱讀時間。 */
final class ShopNoticeLayout {
    private static final long PAGE_MS = 3000L;
    record Page(int from, int to, int index, int count, int height, long durationMs) {}

    private ShopNoticeLayout() {}

    static int rowBudget(int panelHeight, int visibleRows) {
        return rowBudget(panelHeight, visibleRows, 96);
    }

    static int rowBudget(int panelHeight, int visibleRows, int reservedTop) {
        return Math.max(40, (panelHeight - reservedTop) / visibleRows - 4);
    }

    static Page page(int lineCount, int maxHeight, int lineHeight, long elapsedMs) {
        int rows = Math.max(1, (maxHeight - 12) / lineHeight);
        boolean paged = lineCount > rows;
        int capacity = paged ? Math.max(1, rows - 1) : rows;
        int count = Math.max(1, (lineCount + capacity - 1) / capacity);
        int index = (int) Math.min(count - 1L, Math.max(0L, elapsedMs) / PAGE_MS);
        int from = index * capacity;
        int to = Math.min(lineCount, from + capacity);
        int height = Math.max(22, (Math.min(lineCount, capacity) + (paged ? 1 : 0)) * lineHeight + 12);
        return new Page(from, to, index, count, height, Math.max(5000L, count * PAGE_MS));
    }
}
