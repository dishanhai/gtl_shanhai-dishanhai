package com.dishanhai.gt_shanhai.common.machine.part.cloud;

/** 山海：雲端管理畫布；各功能區固定邊界，文字不能推擠操作區。 */
public final class StellarCloudLibraryLayout {
    public static final int WIDTH = 312, HEIGHT = 322, COLUMNS = 9, ROWS = 4, PAGE_SIZE = COLUMNS * ROWS;
    public static final Rect HEADER = new Rect(8, 8, 296, 26);
    public static final Rect FILTER = new Rect(8, 40, 190, 22);
    public static final Rect CATALOG = new Rect(8, 68, 190, 88);
    public static final Rect PAGER = new Rect(8, 162, 190, 18);
    public static final Rect TRANSFER = new Rect(206, 40, 98, 88);
    public static final Rect DETAIL = new Rect(206, 136, 98, 86);
    public static final Rect STATUS = new Rect(8, 188, 190, 34);
    public static final Rect INVENTORY = new Rect(8, 228, 172, 86);
    public static final Rect ACCESS = new Rect(188, 228, 116, 86);

    public record Rect(int x, int y, int width, int height) {}

    /** 折疊只記錄本次畫面的呈現狀態，不保存或改動 Session。 */
    public static final class Foldouts {
        private boolean detailsOpen, accessOpen;
        public boolean detailsOpen() { return detailsOpen; }
        public boolean accessOpen() { return accessOpen; }
        public void toggleDetails() { detailsOpen = !detailsOpen; }
        public void toggleAccess() { accessOpen = !accessOpen; }
    }

    public static Rect viewport(Rect panel) {
        return new Rect(panel.x() + 4, panel.y() + 22, panel.width() - 8, panel.height() - 26);
    }

    public static Rect cell(int index) {
        if (index < 0 || index >= PAGE_SIZE) throw new IndexOutOfBoundsException(index);
        return new Rect(CATALOG.x() + 5 + index % COLUMNS * 20, CATALOG.y() + 4 + index / COLUMNS * 20, 20, 20);
    }

    private StellarCloudLibraryLayout() {}
}
