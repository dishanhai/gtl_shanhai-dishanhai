package com.dishanhai.gt_shanhai.common.item.generator;

/** 预览页的行列。菜单、存档和测试共用。 */
public final class PatternGeneratorLayout {
    public static final int ROWS = 15;
    public static final int DEFAULT_COLUMNS = 3;
    public static final int MAX_COLUMNS = 10;

    private PatternGeneratorLayout() {}

    public static int columns(int value) {
        return Math.max(1, Math.min(MAX_COLUMNS, value));
    }

    public static int capacity(int columns) {
        return ROWS * columns(columns);
    }

    public static int pages(int count, int columns) {
        int size = capacity(columns);
        return Math.max(1, (Math.max(0, count) + size - 1) / size);
    }

    public static int page(int value, int count, int columns) {
        return Math.max(0, Math.min(value, pages(count, columns) - 1));
    }

    public static int column(int index) {
        return index / ROWS;
    }

    public static int row(int index) {
        return index % ROWS;
    }
}
