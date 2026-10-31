package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.Arrays;

/** 山海：Integrate AR 排序；只重排槽位，不查世界或改動伺服端資料。 */
public final class HostFxGlassArSort {
    public static final int MAX_SLOTS = Long.SIZE;
    private HostFxGlassArSort() {}

    /** 正在運行優先，再比較本次啟動／最近運行時間，同時刻按槽號。 */
    public static int[] order(int[] slots, boolean[] working, long[] start, long[] lastRun) {
        if (slots == null) return new int[0];
        int[] result = slots.clone();
        for (int i = 1; i < result.length; i++) {
            int slot = result[i];
            int j = i - 1;
            while (j >= 0 && before(slot, result[j], working, start, lastRun)) {
                result[j + 1] = result[j--];
            }
            result[j + 1] = slot;
        }
        return result;
    }

    private static boolean before(int a, int b, boolean[] working, long[] start, long[] lastRun) {
        boolean wa = working != null && a >= 0 && a < working.length && working[a];
        boolean wb = working != null && b >= 0 && b < working.length && working[b];
        if (wa != wb) return wa;
        long ta = at(wa ? start : lastRun, a);
        long tb = at(wb ? start : lastRun, b);
        return ta == tb ? a < b : ta > tb;
    }

    private static long at(long[] values, int slot) {
        return values != null && slot >= 0 && slot < values.length ? values[slot] : 0L;
    }

    /** 模型驗證排序是原列表的置換，避免遺失或重複顯示模組。 */
    public static boolean sameSet(int[] a, int[] b) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int slot : a) {
            int balance = 0;
            for (int value : a) if (value == slot) balance++;
            for (int value : b) if (value == slot) balance--;
            if (balance != 0) return false;
        }
        return true;
    }

    /** 每台活動主機自己的觀測歷史；只在讀數刷新時更新。 */
    public static final class Tracker {
        private final boolean[] working = new boolean[MAX_SLOTS];
        private final long[] start = new long[MAX_SLOTS];
        private final long[] lastRun = new long[MAX_SLOTS];

        public void clear() {
            Arrays.fill(working, false);
            Arrays.fill(start, 0L);
            Arrays.fill(lastRun, 0L);
        }

        public void observe(long tick, int[] slots, byte[] progress, int[] remaining, byte[] reasons) {
            if (tick < 0L || slots == null) return;
            boolean[] present = new boolean[MAX_SLOTS];
            for (int slot : slots) if (slot >= 0 && slot < MAX_SLOTS) present[slot] = true;
            for (int slot = 0; slot < MAX_SLOTS; slot++) {
                if (!present[slot]) {
                    working[slot] = false;
                    start[slot] = lastRun[slot] = 0L;
                    continue;
                }
                int reason = reasons != null && slot < reasons.length ? reasons[slot] : -1;
                int pct = progress != null && slot < progress.length ? progress[slot] : -1;
                int remain = remaining != null && slot < remaining.length ? remaining[slot] : -1;
                if (reason < 0 && pct < 0) continue;
                // 權威原因優先：缺電／堵塞時仍可能保留進度，不能繼續視作正在運行。
                boolean running = reason >= 0 ? reason == HostFxGlassArModel.REASON_WORKING
                        : pct > 0 || remain > 0;
                if (running) {
                    if (!working[slot]) start[slot] = tick;
                    lastRun[slot] = tick;
                } else if (working[slot]) {
                    lastRun[slot] = tick;
                }
                working[slot] = running;
            }
        }

        public int[] order(int[] slots) {
            return HostFxGlassArSort.order(slots, working, start, lastRun);
        }
    }
}
