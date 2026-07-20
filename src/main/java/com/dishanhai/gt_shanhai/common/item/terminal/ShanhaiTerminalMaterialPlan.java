package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ShanhaiTerminalMaterialPlan {

    public record Allocation(long fromPlayer, long fromAe, long toCraft) {}

    private final Map<String, Long> required = new LinkedHashMap<>();

    public void require(String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0) return;
        required.merge(key, amount, ShanhaiTerminalMaterialPlan::saturatedAdd);
    }

    public Map<String, Long> required() {
        return Collections.unmodifiableMap(required);
    }

    public Map<String, Allocation> allocate(Map<String, Long> playerStock, Map<String, Long> aeStock) {
        Map<String, Allocation> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : required.entrySet()) {
            long need = entry.getValue();
            long fromPlayer = Math.min(need, positiveAmount(playerStock.get(entry.getKey())));
            long afterPlayer = need - fromPlayer;
            long fromAe = Math.min(afterPlayer, positiveAmount(aeStock.get(entry.getKey())));
            result.put(entry.getKey(), new Allocation(fromPlayer, fromAe, afterPlayer - fromAe));
        }
        return Collections.unmodifiableMap(result);
    }

    private static long positiveAmount(Long amount) {
        return amount == null ? 0 : Math.max(0, amount);
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }
}
