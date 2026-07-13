package com.dishanhai.gt_shanhai.common.shop;

/**
 * 山海商店网格的纯坐标计算：集中提供可视条目范围和鼠标命中索引，
 * 让渲染、点击与 tooltip 不再各自线性扫描全部商品。
 */
public final class ShopGridViewport {

    private ShopGridViewport() {}

    public record Range(int fromInclusive, int toExclusive) {}

    public static Range visibleRange(int itemCount, int columns, int scroll,
                                     int viewportHeight, int rowStride, int overscanRows) {
        if (itemCount <= 0 || columns <= 0 || viewportHeight <= 0 || rowStride <= 0) {
            return new Range(0, 0);
        }
        int overscan = Math.max(0, overscanRows);
        int firstRow = Math.max(0, scroll / rowStride - overscan);
        int lastRow = Math.max(firstRow,
                (scroll + viewportHeight + rowStride - 1) / rowStride + overscan);
        return new Range(
                Math.min(itemCount, firstRow * columns),
                Math.min(itemCount, lastRow * columns));
    }

    public static int indexAt(int itemCount, int columns, int columnStride, int rowStride,
                              int cellWidth, int cellHeight, int left, int top, int width, int height,
                              int scroll, double mouseX, double mouseY) {
        if (itemCount <= 0 || columns <= 0 || columnStride <= 0 || rowStride <= 0
                || cellWidth <= 0 || cellHeight <= 0) {
            return -1;
        }
        if (mouseX < left || mouseY < top || mouseX >= left + width || mouseY >= top + height) {
            return -1;
        }
        int localX = (int) mouseX - left;
        int localY = (int) mouseY - top + scroll;
        if (localX < 0 || localY < 0) return -1;
        int col = localX / columnStride;
        int row = localY / rowStride;
        if (col >= columns || localX % columnStride >= cellWidth || localY % rowStride >= cellHeight) {
            return -1;
        }
        int index = row * columns + col;
        return index < itemCount ? index : -1;
    }
}
