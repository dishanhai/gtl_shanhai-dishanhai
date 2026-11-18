package com.dishanhai.gt_shanhai.common.machine.part;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.ToLongBiFunction;

/** Exact secondary storage for output that cannot fit in the upstream long buffer. */
public final class StellarOverflowBuffer {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private StellarOverflowBuffer() {}

    public static <K> long store(Map<K, BigInteger> overflow, K key, long current, long amount) {
        if (current < 0 || amount < 0) throw new IllegalArgumentException("negative buffer amount");
        long accepted = Math.min(amount, Long.MAX_VALUE - current);
        long remainder = amount - accepted;
        if (remainder > 0) overflow.merge(key, BigInteger.valueOf(remainder), BigInteger::add);
        return current + accepted;
    }

    /** Moves at most one long-sized chunk per visited key. Only confirmed inserts are removed. */
    public static <K> long flush(Map<K, BigInteger> overflow, int budget, ToLongBiFunction<K, Long> insert) {
        if (budget <= 0) return 0;
        long total = 0;
        var keys = new ArrayList<K>(Math.min(budget, overflow.size()));
        for (K key : overflow.keySet()) {
            if (keys.size() >= budget) break;
            keys.add(key);
        }
        for (K key : keys) {
            BigInteger before = overflow.get(key);
            if (before == null || before.signum() <= 0) continue;
            long request = before.min(LONG_MAX).longValueExact();
            long accepted = Math.max(0, Math.min(request, insert.applyAsLong(key, request)));
            BigInteger remaining = overflow.remove(key).subtract(BigInteger.valueOf(accepted));
            if (remaining.signum() > 0) overflow.put(key, remaining);
            total = total > Long.MAX_VALUE - accepted ? Long.MAX_VALUE : total + accepted;
        }
        return total;
    }
}
