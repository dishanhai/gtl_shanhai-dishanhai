package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import java.math.BigInteger;

/** 山海：先完成整批能源分配預檢，不足時不產生可執行的局部分配。 */
public final class GuixuEnergyTransfer {
    private GuixuEnergyTransfer() {}

    public static boolean generatesEnergy(BigInteger nativeTickEu) {
        return nativeTickEu != null && nativeTickEu.signum() > 0;
    }

    public static long[] plan(BigInteger amount, long[] available) {
        if (amount.signum() < 0) throw new IllegalArgumentException("能量需求不得為負");
        long[] plan = new long[available.length];
        BigInteger remaining = amount;
        for (int i = 0; i < available.length && remaining.signum() > 0; i++) {
            long part = remaining.min(BigInteger.valueOf(Math.max(0L, available[i]))).longValueExact();
            plan[i] = part;
            remaining = remaining.subtract(BigInteger.valueOf(part));
        }
        return remaining.signum() == 0 ? plan : null;
    }
}
