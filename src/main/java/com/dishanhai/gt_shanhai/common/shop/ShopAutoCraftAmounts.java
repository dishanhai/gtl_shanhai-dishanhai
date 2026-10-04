package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure amount accounting for the dishanhai shop. */
public final class ShopAutoCraftAmounts {
    private ShopAutoCraftAmounts() {}

    static <K> void add(Map<K, BigInteger> amounts, K key, BigInteger amount) {
        if (key != null && amount.signum() > 0) amounts.merge(key, amount, BigInteger::add);
    }

    static <K> BigInteger consume(Map<K, BigInteger> demand, K key, BigInteger available) {
        BigInteger needed = demand.getOrDefault(key, BigInteger.ZERO);
        BigInteger used = needed.min(available.max(BigInteger.ZERO));
        BigInteger remaining = needed.subtract(used);
        if (remaining.signum() > 0) demand.put(key, remaining);
        else demand.remove(key);
        return used;
    }

    static <K> Map<K, BigInteger> missing(Map<K, BigInteger> demand,
            Map<K, BigInteger> stock, Map<K, BigInteger> pending) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        for (Map.Entry<K, BigInteger> entry : demand.entrySet()) {
            BigInteger amount = entry.getValue()
                    .subtract(stock.getOrDefault(entry.getKey(), BigInteger.ZERO).max(BigInteger.ZERO))
                    .subtract(pending.getOrDefault(entry.getKey(), BigInteger.ZERO).max(BigInteger.ZERO));
            add(result, entry.getKey(), amount);
        }
        return result;
    }

    static <K> Map<K, BigInteger> reserve(Map<K, BigInteger> demand, Map<K, BigInteger> stock) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        for (Map.Entry<K, BigInteger> entry : demand.entrySet()) {
            add(result, entry.getKey(), entry.getValue()
                    .min(stock.getOrDefault(entry.getKey(), BigInteger.ZERO).max(BigInteger.ZERO)));
        }
        return result;
    }

    public static long remainingStock(long stored, long reserved) {
        return Math.max(0L, Math.max(0L, stored) - Math.max(0L, reserved));
    }

    static long pendingOutput(long originalAmount, long remainingAmount, boolean returnsToNetwork) {
        return returnsToNetwork ? Math.min(Math.max(0L, originalAmount), Math.max(0L, remainingAmount)) : 0L;
    }
}
