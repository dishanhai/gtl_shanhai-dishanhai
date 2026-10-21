package com.dishanhai.gt_shanhai.client.gui.shop;

/**
 * 商店弹窗列表的行窗口（山海署名，纯 Java）：行高可以不同（名字换行会长高），按「起始行」滚动；
 * 另带滚动条把手的几何换算。全是纯函数，弹窗每帧按当前行高现算。
 */
final class ShopDialogRows {

    static final int MIN_THUMB = 8;

    private ShopDialogRows() {}

    /** 从 {@code first} 起，{@code listH} 内放得下几行；至少 1 行（单行比列表还高时照样显示，不卡住）。 */
    static int fitFrom(int[] heights, int first, int listH) {
        int used = 0;
        int n = 0;
        for (int i = Math.max(0, first); i < heights.length; i++) {
            if (n > 0 && used + heights[i] > listH) break;
            used += heights[i];
            n++;
        }
        return n;
    }

    /** 滚到底时的起始行：最后一屏刚好放满。 */
    static int maxFirst(int[] heights, int listH) {
        int used = 0;
        for (int i = heights.length - 1; i >= 0; i--) {
            used += heights[i];
            if (used > listH) return Math.min(i + 1, heights.length - 1);
        }
        return 0;
    }

    /** 让 {@code index} 那一行完整露出：在上面就顶到第一行，在下面就让它成为最后一行，已露出就不动。 */
    static int scrollToShow(int[] heights, int first, int index, int listH) {
        if (index < first) return index;
        if (index < first + fitFrom(heights, first, listH)) return first;
        int used = 0;
        int start = index;
        for (int i = index; i >= 0; i--) {
            if (i != index && used + heights[i] > listH) break;
            used += heights[i];
            start = i;
        }
        return start;
    }

    /** 滚动条把手 {y, h}（相对轨道顶）：高度按可见行占比，至少 {@link #MIN_THUMB}；没溢出时占满轨道。 */
    static int[] thumb(int total, int visible, int first, int maxFirst, int trackH) {
        if (total <= 0 || maxFirst <= 0 || visible >= total) return new int[]{0, trackH};
        int h = Math.min(trackH, Math.max(MIN_THUMB, (int) ((long) trackH * visible / total)));
        int y = (int) ((long) (trackH - h) * Math.min(first, maxFirst) / maxFirst);
        return new int[]{y, h};
    }

    /** 按住轨道某处（相对轨道顶）时的起始行：把手中心对准游标，越界夹到两端。 */
    static int firstAt(double offsetInTrack, int thumbH, int trackH, int maxFirst) {
        int span = trackH - thumbH;
        if (span <= 0 || maxFirst <= 0) return 0;
        double t = (offsetInTrack - thumbH / 2.0) / span;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return (int) Math.round(t * maxFirst);
    }
}
