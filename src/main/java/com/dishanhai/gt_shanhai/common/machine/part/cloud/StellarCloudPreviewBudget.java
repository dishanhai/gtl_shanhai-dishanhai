package com.dishanhai.gt_shanhai.common.machine.part.cloud;

/** 山海：輸出名稱搜尋分 tick 索引，重複的文字／格位供應器共用同一預算。 */
final class StellarCloudPreviewBudget {
    private long tick = Long.MIN_VALUE;
    private int used;

    boolean take(long at) {
        if (tick != at) { tick = at; used = 0; }
        if (used >= 16) return false;
        used++;
        return true;
    }
}
