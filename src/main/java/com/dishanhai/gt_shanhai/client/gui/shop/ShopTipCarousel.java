package com.dishanhai.gt_shanhai.client.gui.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 山海商店小提示：兩行分頁、閱讀時間及底部水平區域，與 Minecraft 繪製解耦。 */
final class ShopTipCarousel<L> {
    private static final long PAGE_MS = 8000L;
    private static final int PAGE_LINES = 2;

    record Tip(long key, String text) {
        Tip { text = text == null ? "" : text; }

        boolean hasText() {
            return !text.replaceAll("(?i)[§&][0-9a-fk-or]", "").isBlank();
        }
    }

    record Page<L>(Tip tip, List<L> lines, int tipIndex, int tipCount, int pageIndex, int pageCount) {}

    record Layout(int operationX, int operationWidth, int tipX, int tipWidth, int pageX) {}

    private List<Tip> sourceTips = List.of();
    private List<Tip> tips = List.of();
    private List<List<L>> wrapped = List.of();
    private int width = -1;
    private int tipIndex;
    private int pageIndex;
    private long elapsedMs;
    private long lastMs = Long.MIN_VALUE;
    private boolean wasPaused;

    static boolean isTipCategory(boolean hidden, String... path) {
        if (hidden) return false;
        for (String name : path) {
            if ("提示".equals(name) || "小提示".equals(name)) return true;
        }
        return false;
    }

    /** sourceTips 保留輸入快照，空白項目不會讓每幀重複過濾或重新換行。 */
    void setTips(List<Tip> next, int nextWidth, Function<String, List<L>> wrap) {
        if (width == nextWidth && sourceTips.equals(next)) return;
        Tip previous = tips.isEmpty() ? null : tips.get(tipIndex);
        boolean resized = width != nextWidth;
        sourceTips = List.copyOf(next);
        List<Tip> visible = new ArrayList<>();
        List<List<L>> lines = new ArrayList<>();
        for (Tip tip : sourceTips) {
            if (!tip.hasText()) continue;
            visible.add(tip);
            lines.add(List.copyOf(wrap.apply(tip.text())));
        }
        tips = List.copyOf(visible);
        wrapped = List.copyOf(lines);
        width = nextWidth;
        int retained = previous == null ? -1 : tips.indexOf(previous);
        tipIndex = Math.max(0, retained);
        // 重新換行後頁碼已不代表相同文字位置，重播本則，避免放大視窗跳過未讀文字。
        pageIndex = retained < 0 || resized ? 0 : Math.min(pageIndex, pageCount() - 1);
        if (retained < 0 || resized) resetClock();
    }

    void tick(long nowMs, boolean paused) {
        if (lastMs == Long.MIN_VALUE) {
            lastMs = nowMs;
            wasPaused = paused;
            return;
        }
        long delta = Math.max(0L, nowMs - lastMs);
        lastMs = nowMs;
        boolean skip = paused || wasPaused || tips.isEmpty();
        wasPaused = paused;
        if (skip) return;
        elapsedMs += delta;
        if (elapsedMs < PAGE_MS) return;
        // 視窗停頓後只前進一頁，不能跳過尚未讀過的長文。
        elapsedMs = 0L;
        if (++pageIndex >= pageCount()) {
            pageIndex = 0;
            tipIndex = (tipIndex + 1) % tips.size();
        }
    }

    Page<L> page() {
        if (tips.isEmpty()) return null;
        List<L> lines = wrapped.get(tipIndex);
        int from = Math.min(lines.size(), pageIndex * PAGE_LINES);
        int to = Math.min(lines.size(), from + PAGE_LINES);
        return new Page<>(tips.get(tipIndex), lines.subList(from, to), tipIndex, tips.size(),
                pageIndex, pageCount());
    }

    void clear() {
        sourceTips = List.of();
        tips = List.of();
        wrapped = List.of();
        width = -1;
        tipIndex = 0;
        pageIndex = 0;
        resetClock();
    }

    static Layout layout(int listX, int listWidth, int pageWidth, boolean hasTips) {
        int x = listX + 4;
        int pageX = listX + listWidth - pageWidth - 2;
        int available = Math.max(0, pageX - 6 - x);
        if (!hasTips || available < 3) return new Layout(x, available, pageX, 0, pageX);
        int gap = Math.min(8, available / 8);
        int operationWidth = Math.min(190, (available - gap) / 3);
        int tipX = x + operationWidth + gap;
        return new Layout(x, operationWidth, tipX, available - operationWidth - gap, pageX);
    }

    private int pageCount() {
        return tips.isEmpty() ? 1 : Math.max(1, (wrapped.get(tipIndex).size() + PAGE_LINES - 1) / PAGE_LINES);
    }

    private void resetClock() {
        elapsedMs = 0L;
        lastMs = Long.MIN_VALUE;
        wasPaused = false;
    }
}
