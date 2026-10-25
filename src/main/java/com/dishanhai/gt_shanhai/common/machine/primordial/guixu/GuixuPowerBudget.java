package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import java.math.BigInteger;

/** 山海：同一份功率預算同時支撐並行與速度，所有能量計算保持整數精度。 */
public final class GuixuPowerBudget {
    private GuixuPowerBudget() {}

    public static int duration(BigInteger power, BigInteger referenceEut, int baseTicks, int minimumTicks) {
        int base = Math.max(1, baseTicks);
        int minimum = Math.max(1, minimumTicks);
        BigInteger speed = power.max(BigInteger.ZERO).divide(referenceEut.max(BigInteger.ONE))
                .sqrt().max(BigInteger.ONE).min(BigInteger.valueOf(base));
        int ticks = ceilDivide(BigInteger.valueOf(base), speed).intValueExact();
        return Math.max(minimum, ticks);
    }

    /** 達最短耗時前，並行與速度同階成長，避免整 tick 餘量使升級反降吞吐。 */
    public static long parallelLimit(BigInteger power, BigInteger referenceEut, int baseTicks,
                                     int minimumTicks, long nativeLimit) {
        if (power.signum() <= 0 || nativeLimit <= 0) return 0L;
        if (duration(power, referenceEut, baseTicks, minimumTicks) <= Math.max(1, minimumTicks)) return nativeLimit;
        return power.divide(referenceEut.max(BigInteger.ONE)).sqrt()
                .min(BigInteger.valueOf(nativeLimit)).longValueExact();
    }

    /** 先按能量與全局並行上限均分，未使用的份額再分配；不逐份迴圈。 */
    public static long[] allocate(BigInteger power, int ticks, BigInteger[] costs, long[] wants,
                                  BigInteger stored, long parallelLimit) {
        if (costs.length != wants.length || ticks <= 0) throw new IllegalArgumentException("Invalid Guixu batch");
        long[] result = new long[wants.length];
        if (power.signum() <= 0 || parallelLimit <= 0 || wants.length == 0) return result;
        BigInteger tickCount = BigInteger.valueOf(ticks);
        BigInteger budget = power.multiply(tickCount);
        if (stored != null) budget = budget.min(stored.max(BigInteger.ZERO).divide(tickCount).multiply(tickCount));
        BigInteger share = budget.divide(BigInteger.valueOf(wants.length));
        long remainingParallel = parallelLimit;
        for (int i = 0; i < wants.length; i++) {
            int remainingRecipes = wants.length - i;
            long fairLimit = remainingParallel / remainingRecipes + (remainingParallel % remainingRecipes == 0 ? 0 : 1);
            long give = amount(share.min(budget), costs[i], wants[i], fairLimit);
            result[i] = give;
            remainingParallel -= give;
            budget = budget.subtract(costs[i].max(BigInteger.ONE).multiply(BigInteger.valueOf(give)));
        }
        for (int i = 0; i < wants.length && remainingParallel > 0; i++) {
            long give = amount(budget, costs[i], Math.max(0L, wants[i] - result[i]), remainingParallel);
            result[i] += give;
            remainingParallel -= give;
            budget = budget.subtract(costs[i].max(BigInteger.ONE).multiply(BigInteger.valueOf(give)));
        }
        return result;
    }

    private static long amount(BigInteger budget, BigInteger cost, long want, long limit) {
        return budget.divide(cost.max(BigInteger.ONE)).min(BigInteger.valueOf(Math.max(0L, want)))
                .min(BigInteger.valueOf(Math.max(0L, limit))).longValueExact();
    }

    public static BigInteger ceilDivide(BigInteger numerator, BigInteger denominator) {
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        return qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
    }

    /** 原生 factory 會除以 tick 再反號；耗電向上、發電向下取整。 */
    public static BigInteger roundForTicks(BigInteger signedTotal, int ticks) {
        BigInteger count = BigInteger.valueOf(ticks);
        return (signedTotal.signum() > 0 ? ceilDivide(signedTotal, count) : signedTotal.divide(count)).multiply(count);
    }
}
