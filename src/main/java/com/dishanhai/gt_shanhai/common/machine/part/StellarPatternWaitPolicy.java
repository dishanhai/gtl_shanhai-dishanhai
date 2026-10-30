package com.dishanhai.gt_shanhai.common.machine.part;

/** 山海：只觀測並行競爭與進度，不介入主機的分配或扣料。每個槽／主機各持有一份。 */
final class StellarPatternWaitPolicy {
    private boolean initialized;
    private Object batch;
    private int progress;
    private long lastAdvance;
    private long cycleBudget;
    private long missedCycles;
    private long previousSuccessfulTicks = -1L;

    boolean observe(long now, long timeout, long capacity, long allocated, long queuedSlots,
                    boolean working, boolean failed, int currentProgress, long successfulTicks,
                    int duration, Object currentBatch) {
        boolean successfulAdvance = successfulTicks >= 0L && previousSuccessfulTicks >= 0L
                && successfulTicks > previousSuccessfulTicks;
        long completed = 0L;
        if (initialized && working && batch != null && batch == currentBatch && successfulAdvance && duration > 0) {
            // 每秒取樣可能正好落在同一進度；成功運行時間仍能證明中間已完整跑過短批次。
            long phaseDelta = (successfulTicks - currentProgress) - (previousSuccessfulTicks - progress);
            if (phaseDelta >= duration) completed = phaseDelta / duration;
        }
        boolean wrapped = initialized && working && batch != null && batch == currentBatch && currentProgress < progress;
        boolean switched = initialized && batch != null && currentBatch != null && batch != currentBatch;
        if (wrapped || switched) completed = Math.max(1L, completed);
        boolean advanced = initialized && working && (currentProgress > progress || successfulAdvance
                || (currentBatch != null && batch != currentBatch) || wrapped);
        if (!initialized || advanced) lastAdvance = now;
        if (cycleBudget > 0L) missedCycles = add(missedCycles, completed);
        batch = currentBatch;
        progress = currentProgress;
        previousSuccessfulTicks = successfulTicks;
        initialized = true;

        boolean competing = capacity > 0L && queuedSlots > 0L
                && (allocated >= capacity || queuedSlots > capacity - Math.max(0L, allocated));
        if (competing && cycleBudget == 0L) {
            // 固定本槽的等待機會，後續補料不能一直擴大額度而掩蓋飢餓。
            long contenders = add(queuedSlots, 1L);
            cycleBudget = contenders / capacity + (contenders % capacity == 0L ? 0L : 1L);
        }
        return working && !failed && competing && missedCycles < cycleBudget
                && now - lastAdvance >= 0L && now - lastAdvance < timeout;
    }

    boolean observe(long now, long timeout, long capacity, long allocated, long queuedSlots,
                    boolean working, boolean failed, int currentProgress, Object currentBatch) {
        return observe(now, timeout, capacity, allocated, queuedSlots, working, failed,
                currentProgress, -1L, 0, currentBatch);
    }

    long progressDeadline(long timeout) {
        return add(lastAdvance, timeout);
    }

    static long multiply(long a, long b) {
        if (a <= 0L || b <= 0L) return -1L;
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    static long add(long a, long b) {
        a = Math.max(0L, a); b = Math.max(0L, b);
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
