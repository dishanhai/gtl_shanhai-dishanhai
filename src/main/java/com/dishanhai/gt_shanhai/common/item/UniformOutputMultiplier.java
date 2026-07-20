package com.dishanhai.gt_shanhai.common.item;

import java.util.Map;
import java.util.Set;

final class UniformOutputMultiplier {

    private UniformOutputMultiplier() {}

    static <K> long detect(Map<K, Long> baseAmounts, Map<K, Long> patternAmounts, Set<K> unscaledKeys) {
        if (baseAmounts == null || patternAmounts == null || baseAmounts.isEmpty()
                || !baseAmounts.keySet().equals(patternAmounts.keySet())) {
            return 0L;
        }

        long multiplier = 0L;
        for (Map.Entry<K, Long> entry : baseAmounts.entrySet()) {
            long baseAmount = amount(entry.getValue());
            long patternAmount = amount(patternAmounts.get(entry.getKey()));
            if (baseAmount <= 0L || patternAmount <= 0L) return 0L;

            if (unscaledKeys != null && unscaledKeys.contains(entry.getKey())) {
                if (baseAmount != patternAmount) return 0L;
                continue;
            }
            if (patternAmount % baseAmount != 0L) return 0L;

            long current = patternAmount / baseAmount;
            if (current <= 0L) return 0L;
            if (multiplier == 0L) {
                multiplier = current;
            } else if (multiplier != current) {
                return 0L;
            }
        }
        return multiplier == 0L ? 1L : multiplier;
    }

    private static long amount(Long value) {
        return value == null ? 0L : value.longValue();
    }
}
