package com.dishanhai.gt_shanhai.client.gui.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 商品编辑器的页签与排版（山海署名，纯 Java）：五个页签各管哪些行，行按实际高度往下堆
 * （说明换行就长高，被隐藏的行不占位），超出可视区时按像素滚动。全是纯函数，编辑器每帧按当前行高现算。
 */
final class ShopEntryEditLayout {

    enum Tab {
        GOODS("商品"), PRICE("价格"), LIMIT("限购"), REWARD("奖励"), LINK("跳转");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** 编辑器的每一行；声明顺序就是页内从上到下的顺序。 */
    enum Row {
        GOODS(Tab.GOODS), NAME(Tab.GOODS), COUNT(Tab.GOODS), CATEGORY(Tab.GOODS), DESC(Tab.GOODS), ICONS(Tab.GOODS),
        FREE(Tab.PRICE), SPARK(Tab.PRICE), COIN(Tab.PRICE), ITEM(Tab.PRICE), FLUID(Tab.PRICE), PRICE_HINT(Tab.PRICE),
        DISCOUNT(Tab.PRICE),
        USES(Tab.LIMIT), PERIOD(Tab.LIMIT), TRADE(Tab.LIMIT), PREREQ(Tab.LIMIT), SUBMISSION(Tab.LIMIT),
        REWARD_MODE(Tab.REWARD), REWARD_POOL(Tab.REWARD),
        HIDDEN(Tab.LINK), LINK_KEY(Tab.LINK), LINK_TO(Tab.LINK);

        final Tab tab;

        Row(Tab tab) {
            this.tab = tab;
        }
    }

    private static final Map<Tab, List<Row>> ROWS = new EnumMap<>(Tab.class);

    static {
        for (Tab t : Tab.values()) ROWS.put(t, new ArrayList<>());
        for (Row r : Row.values()) ROWS.get(r.tab).add(r);
        for (Tab t : Tab.values()) ROWS.put(t, Collections.unmodifiableList(ROWS.get(t)));
    }

    private ShopEntryEditLayout() {}

    static List<Row> rowsOf(Tab tab) {
        return ROWS.get(tab);
    }

    /** 各行相对内容顶端的 y：可见行之间隔 {@code gap}；高 0 的行（被隐藏）不占位也不留间距，y 落在下一可见行处。 */
    static int[] stack(int[] heights, int gap) {
        int[] ys = new int[heights.length];
        int cursor = 0;
        boolean any = false;
        for (int i = 0; i < heights.length; i++) {
            if (heights[i] <= 0) {
                ys[i] = any ? cursor + gap : cursor;
                continue;
            }
            if (any) cursor += gap;
            ys[i] = cursor;
            cursor += heights[i];
            any = true;
        }
        // 隐藏行的 y 已按「下一可见行」算好；末尾的隐藏行改回贴着最后一行底边，不越出内容
        for (int i = heights.length - 1; i >= 0 && heights[i] <= 0; i--) ys[i] = cursor;
        return ys;
    }

    /** 内容总高：最后一个可见行的底边，不算结尾的间距。 */
    static int contentHeight(int[] heights, int gap) {
        int total = 0;
        boolean any = false;
        for (int h : heights) {
            if (h <= 0) continue;
            if (any) total += gap;
            total += h;
            any = true;
        }
        return total;
    }

    static int maxScroll(int contentH, int viewH) {
        return Math.max(0, contentH - viewH);
    }

    static int clampScroll(int scroll, int contentH, int viewH) {
        return Math.max(0, Math.min(scroll, maxScroll(contentH, viewH)));
    }

    /** 让 y..y+h 那一行露出来（y 相对内容顶端）：在上面就贴顶，在下面就贴底，比可视区还高先露顶端，已露出不动。 */
    static int scrollToShow(int scroll, int y, int h, int viewH) {
        if (y < scroll || h > viewH) return y;
        if (y + h > scroll + viewH) return y + h - viewH;
        return scroll;
    }

    /** Ctrl+1~5 对应的页签；其他数字返回 null。 */
    static Tab tabForHotkey(int digit) {
        Tab[] tabs = Tab.values();
        return digit >= 1 && digit <= tabs.length ? tabs[digit - 1] : null;
    }

    static Tab cycle(Tab tab, int dir) {
        Tab[] tabs = Tab.values();
        int n = tabs.length;
        return tabs[((tab.ordinal() + dir) % n + n) % n];
    }

    /** 页签从 {@code x0} 起左右并排，各自宽度不同，中间隔 {@code gap}。 */
    static int[] tabXs(int[] widths, int x0, int gap) {
        int[] xs = new int[widths.length];
        int x = x0;
        for (int i = 0; i < widths.length; i++) {
            xs[i] = x;
            x += widths[i] + gap;
        }
        return xs;
    }
}
