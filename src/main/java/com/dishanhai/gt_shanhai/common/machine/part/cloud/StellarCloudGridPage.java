package com.dishanhai.gt_shanhai.common.machine.part.cloud;

/** 山海：篩選／刪除後夾住頁碼，格位只能選取目前頁面的原件索引。 */
final class StellarCloudGridPage {
    static int clamp(int page, int count) { return Math.max(0, Math.min(page, Math.max(0, count - 1) / StellarCloudLibraryLayout.PAGE_SIZE)); }

    static int index(int page, int cell, int count) {
        if (cell < 0 || cell >= StellarCloudLibraryLayout.PAGE_SIZE || count <= 0) return -1;
        long index = (long) clamp(page, count) * StellarCloudLibraryLayout.PAGE_SIZE + cell;
        return index < count ? (int) index : -1;
    }

    private StellarCloudGridPage() {}
}
