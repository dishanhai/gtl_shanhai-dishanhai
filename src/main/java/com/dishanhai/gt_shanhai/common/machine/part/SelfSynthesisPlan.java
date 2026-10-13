package com.dishanhai.gt_shanhai.common.machine.part;

/**
 * 自合成发配份数。AE 一次 push 带入的数量除以样板单次用量，得到要回注的产物倍数。
 */
public final class SelfSynthesisPlan {

    private SelfSynthesisPlan() {}

    public static long craftTimes(long[] perCraft, long[] pushed) {
        if (perCraft == null || perCraft.length == 0) {
            return 1L;
        }
        long times = Long.MAX_VALUE;
        for (int i = 0; i < perCraft.length; i++) {
            long need = perCraft[i];
            long have = pushed != null && i < pushed.length ? pushed[i] : 0L;
            if (need <= 0L || have < need) {
                return 0L;
            }
            times = Math.min(times, have / need);
        }
        return times == Long.MAX_VALUE ? 0L : times;
    }

    public static long scaledAmount(long perCraft, long times) {
        if (perCraft <= 0L || times <= 0L) {
            return 0L;
        }
        if (perCraft > Long.MAX_VALUE / times) {
            return Long.MAX_VALUE;
        }
        return perCraft * times;
    }
}
