package com.dishanhai.gt_shanhai.common.machine.part;

/** 山海：原版多配方邏輯的只讀分配觀測；-1 表示尚無觀測，不以聚合輸出樣板推測。 */
public interface StellarPatternParallelState {
    long gtShanhai$getObservedAllocatedParallel();
    void gtShanhai$recordAllocatedParallel(long amount);
}
