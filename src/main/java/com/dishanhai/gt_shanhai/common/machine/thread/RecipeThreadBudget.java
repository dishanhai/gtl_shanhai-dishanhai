package com.dishanhai.gt_shanhai.common.machine.thread;

import java.math.BigInteger;

/** Author: dishanhai. Shared arithmetic for admission without int truncation. */
public final class RecipeThreadBudget {
    private RecipeThreadBudget() {}

    public static long multiply(long a, long b) {
        if (a <= 0L || b <= 0L) return 0L;
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    public static BigInteger remaining(BigInteger capacity, Iterable<BigInteger> signedReservations,
                                       boolean consumer) {
        BigInteger remaining = capacity;
        for (BigInteger value : signedReservations) {
            if (value != null && (consumer ? value.signum() < 0 : value.signum() > 0)) {
                remaining = remaining.subtract(value.abs());
            }
        }
        return remaining.max(BigInteger.ZERO);
    }
}
