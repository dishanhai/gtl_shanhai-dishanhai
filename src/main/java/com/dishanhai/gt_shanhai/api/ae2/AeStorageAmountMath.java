package com.dishanhai.gt_shanhai.api.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

/** AE2 long 数量边界工具：存储源可以无限，但 AE2 接口只能表达 long。 */
public final class AeStorageAmountMath {

    private AeStorageAmountMath() {
    }

    public static long saturatedAdd(long current, long amount) {
        if (amount <= 0L) {
            return current < 0L ? 0L : current;
        }
        if (current < 0L) {
            current = 0L;
        }
        if (current >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (amount > Long.MAX_VALUE - current) {
            return Long.MAX_VALUE;
        }
        return current + amount;
    }

    public static void mergeSaturated(KeyCounter target, KeyCounter source) {
        for (Object2LongMap.Entry<AEKey> entry : source) {
            addSaturated(target, entry.getKey(), entry.getLongValue());
        }
    }

    public static void addSaturated(KeyCounter target, AEKey key, long amount) {
        if (target == null || key == null || amount <= 0L) {
            return;
        }
        target.set(key, saturatedAdd(target.get(key), amount));
    }
}
