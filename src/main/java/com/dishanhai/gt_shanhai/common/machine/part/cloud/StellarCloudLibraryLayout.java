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
    // 山海：文字、槽位與按鈕均由畫布給出固定命中區，收合不搬移常駐操作。
    public static final Rect SEARCH = new Rect(40, 44, 84, 14);
    public static final Rect TYPE = new Rect(130, 42, 64, 18);
    public static final Rect INPUT_SLOT = new Rect(222, 72, 18, 18);
    public static final Rect OUTPUT_SLOT = new Rect(270, 72, 18, 18);
    public static final Rect UPLOAD = new Rect(210, 98, 44, 24);
    public static final Rect WITHDRAW = new Rect(258, 98, 44, 24);
    public static final Rect PREVIOUS = new Rect(PAGER.x(), PAGER.y(), 44, PAGER.height());
    public static final Rect NEXT = new Rect(PAGER.x() + PAGER.width() - 44, PAGER.y(), 44, PAGER.height());

    public record Rect(int x, int y, int width, int height) {}

    /** 與 PlayerInventoryWidget 的原生槽一致；排除熱鍵列上方空隙及右／下邊界。 */
    public static int inventorySlot(double x, double y) {
        x -= 5;
        y -= 5;
        if (x < 0 || x >= 162 || y < 0) return -1;
        int column = (int) x / 18;
        if (y < 54) return ((int) y / 18 + 1) * 9 + column;
        return y >= 58 && y < 76 ? column : -1;
    }

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
