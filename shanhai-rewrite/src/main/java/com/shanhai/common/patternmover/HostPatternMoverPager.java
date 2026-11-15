package com.shanhai.common.patternmover;

/**
 * 面板<b>分页</b>的纯核（不 import 任何 Minecraft / GTCEu / AE2 / LDLib 类 ⇒ 裸 JVM 可跑断言）。
 *
 * <h2>一、为什么分页要单独抽出来</h2>
 * 分页规则只有三条，但每一条都容易在面板里写错：
 * <pre>
 *   ① 每页 8 行（{@link #ROWS}），组数 G ⇒ 页数 N = ceil(G / 8)，G = 0 时仍然是 1 页（显示空列表）
 *   ② 页号是【面板本地的界面状态】，越界一律夹住：page ∈ [0, N-1]
 *   ③ 组数变少后（取出之后）页数要重算，<b>当前页越界就夹到最后一页</b>
 * </pre>
 * 第 ③ 条是本工程既有的同族口径（配方编辑器工作区同样是「页号保留、页数变少了才夹到最后一页」）。
 *
 * <h2>二、自检里的读数（{@link HostPatternMoverSelfCheck} c19）</h2>
 * 17 组 ⇒ 3 页（8 / 8 / 1）；剩 10 组 ⇒ 2 页，且原先停在第 3 页（下标 2）时被夹到下标 1。
 */
public final class HostPatternMoverPager {

    /** 每页几行（用户口径：每页 8 行）。 */
    public static final int ROWS = 8;

    private HostPatternMoverPager() {}

    /** 组数 ⇒ 页数（<b>至少 1 页</b>：0 组时面板仍要显示一页空列表，不然页码会写成「0 / 0」）。 */
    public static int pages(int groups) {
        final int g = Math.max(0, groups);
        return g == 0 ? 1 : (g + ROWS - 1) / ROWS;
    }

    /** 把页号夹进合法区间（负的 ⇒ 0；超过末页 ⇒ 末页）。 */
    public static int clamp(int page, int groups) {
        final int last = pages(groups) - 1;
        if (page < 0) {
            return 0;
        }
        return Math.min(page, last);
    }

    /** 本页第一行的<b>绝对</b>行号（绝对行号 = 该组在分组表里的下标）。 */
    public static int rowStart(int page, int groups) {
        return clamp(page, groups) * ROWS;
    }

    /** 本页显示几行（末页可能不足 8 行）。 */
    public static int rowsOnPage(int page, int groups) {
        final int g = Math.max(0, groups);
        final int start = rowStart(page, g);
        return Math.min(ROWS, Math.max(0, g - start));
    }

    /** 这一行（本页内的 0..7）对应的绝对行号；越界 ⇒ -1。 */
    public static int absoluteRow(int page, int row, int groups) {
        if (row < 0 || row >= ROWS) {
            return -1;
        }
        final int abs = rowStart(page, groups) + row;
        return abs < Math.max(0, groups) ? abs : -1;
    }

    /** 还能往前翻吗。 */
    public static boolean hasPrev(int page, int groups) {
        return clamp(page, groups) > 0;
    }

    /** 还能往后翻吗。 */
    public static boolean hasNext(int page, int groups) {
        return clamp(page, groups) < pages(groups) - 1;
    }

    /** 下一页（到头停住，不循环 —— 循环会让人不知道自己在第几页）。 */
    public static int next(int page, int groups) {
        return clamp(clamp(page, groups) + 1, groups);
    }

    /** 上一页（到头停住）。 */
    public static int prev(int page, int groups) {
        return clamp(clamp(page, groups) - 1, groups);
    }

    /** 一行式读数（日志/断言用，格式稳定）。 */
    public static String describe(int page, int groups) {
        return "page=" + clamp(page, groups) + "/" + pages(groups)
                + " rows=" + rowsOnPage(page, groups) + " startRow=" + rowStart(page, groups)
                + " groups=" + groups;
    }

    // ================================================================ 行计划（第四刀 ③）

    /**
     * <b>面板这一页该建几行、每行对应哪一组</b>（= 「重建分组列表控件」要的那份计划）。
     *
     * <h2>为什么单独抽一份记录出来</h2>
     * 用户实测（2026-11 第四刀）：「页面点击取出后仍然没有实时刷新（分组没消失），点击清空也是」。
     * 改前面板的行控件是<b>构造时一次建死</b>的：每行一个标签 ＋ 一个「取出」按钮，永远 8 行都在
     * ——组数变少之后，多出来的那些行只是标签文本变成空串，<b>「取出」按钮还杵在那里</b>，
     * 于是看起来就像「分组没消失」。
     * ⇒ 现在每次回执到达都<b>整份重算这份计划</b>，面板据此把「不该存在的行」连按钮一起隐藏、
     * 并在 0 组时显示空提示行。计划是纯数据，所以「3 组 → 取出 1 组 ⇒ 2 行」「清空 ⇒ 0 行」这两条
     * 判据可以在裸 JVM 里断言（{@code HostPatternMoverSelfCheck} C26 组）。
     *
     * @param page   已夹紧的页号（0 基）
     * @param pages  总页数（≥1）
     * @param rows   本页要显示几行（0..{@link #ROWS}；0 ⇒ 列表为空，面板显示空提示）
     * @param startRow 本页第一行的绝对行号
     * @param groups 总组数
     */
    public record View(int page, int pages, int rows, int startRow, int groups) {

        /** 列表现为空（0 组）：面板要显示「没有样板」提示行。 */
        public boolean empty() {
            return this.groups <= 0 || this.rows <= 0;
        }

        /** 本页第 {@code row} 行（0 基）的绝对行号；超出本页行数 ⇒ -1（= 这一行不该存在）。 */
        public int absoluteRow(int row) {
            if (row < 0 || row >= this.rows) {
                return -1;
            }
            return this.startRow + row;
        }

        /** 一行式读数（日志/断言用，格式稳定）。 */
        public String describe() {
            return "page=" + this.page + "/" + this.pages + " rows=" + this.rows
                    + " startRow=" + this.startRow + " groups=" + this.groups
                    + (empty() ? " empty=true" : "");
        }
    }

    /** 算一份行计划（页号会先按组数夹紧 —— 组数变少之后老页号自动落到最后一页）。 */
    public static View view(int page, int groups) {
        final int g = Math.max(0, groups);
        final int p = clamp(page, g);
        return new View(p, pages(g), rowsOnPage(p, g), rowStart(p, g), g);
    }
}
