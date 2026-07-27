package com.dishanhai.gt_shanhai.common.machine.part;

final class MEDiskHatchPriority {

    private MEDiskHatchPriority() {
    }

    /**
     * 全部槽位挂载在同一优先级上。
     * <p>
     * 此前按槽位递减（basePriority - slot）会让 108~256 个槽位两两互异，把 AE2
     * NetworkStorage 假设只有 1~3 个桶的 TreeMap 撑成上百个优先级桶——insert/extract
     * 外层循环无提前退出、unmount 是 O(挂载数×桶数) 线性移除，等于一台仓室让全网每次
     * 存取付 20~100 倍固定开销（记在 appeng 帧上，按模组归因的火焰图不可见）。
     * 槽位顺序改由 mountInventories 的挂载插入顺序表达：AE2 同一桶内按 List 顺序
     * 遍历，slot 0 天然优先，提取顺序语义不变。
     */
    static int forSlot(int basePriority, int slot) {
        return basePriority;
    }

    static int add(int priority, int delta) {
        long value = (long) priority + delta;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }
}
