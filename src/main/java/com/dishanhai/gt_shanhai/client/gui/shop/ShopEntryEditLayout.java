package com.dishanhai.gt_shanhai.client.gui.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 商品编辑器的页签与排版（山海署名，纯 Java）：五个页签各管哪些行，行按实际高度往下堆
 * （说明换行就长高，被隐藏的行不占位），超出可视区时按像素滚动；外加槽位「替换这一格」的清单拼接。
 * 全是纯函数，编辑器每帧按当前行高现算。
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
        HIDDEN(Tab.LINK), LINK_KEY(Tab.LINK), LINK_TO(Tab.LINK), BRANCH(Tab.LINK);

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

    // 山海：换行规则写在 Kotlin，Java 画面保留薄入口。
    static int perLine(int availableWidth) { return ShopSlotRows.perLine(availableWidth); }
    static int lines(int slotCount, int perLine) { return ShopSlotRows.lines(slotCount, perLine); }
    static int slotX(int originX, int index, int perLine) { return ShopSlotRows.slotX(originX, index, perLine); }
    static int slotY(int originY, int index, int perLine, int rowPitch) {
        return ShopSlotRows.slotY(originY, index, perLine, rowPitch);
    }
    static int slotIndexAt(double mx, double my, int originX, int originY, int slotCount,
                           int perLine, int rowPitch, int hitHeight) {
        return ShopSlotRows.slotIndexAt(mx, my, originX, originY, slotCount, perLine, rowPitch, hitHeight);
    }

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

    /**
     * 选择器「替换这一格」确认后：用 {@code picked} 按顺序换掉 {@code list} 第 {@code at} 项（空 = 删掉这一项）。
     * {@code at} 越界（比如并行的数量表比格子短）就接在末尾。返回新内容插在哪。
     */
    static <T> int replaceAt(List<T> list, int at, List<? extends T> picked) {
        if (at < 0 || at >= list.size()) {
            int end = list.size();
            list.addAll(picked);
            return end;
        }
        list.remove(at);
        list.addAll(at, picked);
        return at;
    }

    /**
     * 选择器「编辑这一排」确认后：整排换成 {@code picked}（点格子时整排都已预填进已选清单，所以不是只换那一格）。
     * 空 = 清空这一排。返回原来排里有几项，调用方拿它决定从哪起弹出新增的格子。
     */
    static <T> int replaceAll(List<T> list, List<? extends T> picked) {
        int before = list.size();
        list.clear();
        list.addAll(picked);
        return before;
    }
}
